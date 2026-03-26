package com.ltp.peter.augtrade.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ltp.peter.augtrade.entity.Kline;
import com.ltp.peter.augtrade.mapper.KlineMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OKX K线数据采集器
 *
 * 作为币安的替代/备用数据源。OKX 公开行情 API 无需 API Key，
 * 境内网络稳定性优于 fapi.binance.com。
 *
 * 品种映射：
 *   OKX instId: XAU-USDT-SWAP（黄金 USDT 永续合约）
 *   存储 symbol: XAUUSDT（与币安保持一致）
 */
@Slf4j
@Component
public class OkxKlineDataFetcher {

    private static final String OKX_BASE_URL = "https://www.okx.com";
    private static final String OKX_INST_ID = "XAU-USDT-SWAP";

    @Autowired
    private KlineMapper klineMapper;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 采集最近 N 根已完成的 5m K线，写入数据库。
     *
     * 建议 limit=3，正常情况只有 1 根新数据（另 2 根因唯一索引自动跳过），
     * 上次请求失败时自动补上漏掉的 K线。
     *
     * @param targetSymbol 存入数据库使用的 symbol（如 "XAUUSDT"）
     * @param limit        获取根数（建议 3）
     * @return 实际新增的 K线数，-1 表示请求失败
     */
    public int collectLatestKlines(String targetSymbol, int limit) {
        // history-candles 只返回已完成的 K线（不含当前正在形成中的），避免存入未闭合数据
        String url = String.format(
                "%s/api/v5/market/history-candles?instId=%s&bar=5m&limit=%d",
                OKX_BASE_URL, OKX_INST_ID, limit);

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("[OKX] K线请求失败: HTTP {}", response.code());
                return -1;
            }

            JsonNode root = objectMapper.readTree(response.body().string());
            if (!"0".equals(root.path("code").asText())) {
                log.warn("[OKX] API 返回错误: {}", root.path("msg").asText());
                return -1;
            }

            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() == 0) {
                log.warn("[OKX] 返回空数据");
                return -1;
            }

            // OKX K线格式（降序，最新在前）：
            // [ts, open, high, low, close, vol, volCcy, volCcyQuote, confirm]
            // confirm="1" 表示已完成，"0" 表示当前正在形成中，不存未完成K线
            List<Kline> toSave = new ArrayList<>();
            for (JsonNode item : data) {
                String confirm = item.size() > 8 ? item.get(8).asText() : "1";
                if (!"1".equals(confirm)) {
                    log.debug("[OKX] 跳过未完成K线: ts={}", item.get(0).asLong());
                    continue;
                }
                long ts = item.get(0).asLong();
                Kline kline = new Kline();
                kline.setSymbol(targetSymbol);
                kline.setInterval("5m");
                kline.setTimestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()));
                kline.setOpenPrice(new BigDecimal(item.get(1).asText()));
                kline.setHighPrice(new BigDecimal(item.get(2).asText()));
                kline.setLowPrice(new BigDecimal(item.get(3).asText()));
                kline.setClosePrice(new BigDecimal(item.get(4).asText()));
                kline.setVolume(new BigDecimal(item.get(5).asText()));
                kline.setAmount(new BigDecimal(item.get(7).asText()));
                kline.setCreateTime(LocalDateTime.now());
                kline.setUpdateTime(LocalDateTime.now());
                toSave.add(kline);
            }

            int inserted = 0;
            for (Kline kline : toSave) {
                try {
                    klineMapper.insert(kline);
                    inserted++;
                    log.info("[OKX] ✅ K线保存: {} O={} H={} L={} C={} V={}",
                            kline.getTimestamp(), kline.getOpenPrice(), kline.getHighPrice(),
                            kline.getLowPrice(), kline.getClosePrice(), kline.getVolume());
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("Duplicate entry")) {
                        log.debug("[OKX] 重复K线跳过: {}", kline.getTimestamp());
                    } else {
                        log.warn("[OKX] K线插入失败: {} - {}", kline.getTimestamp(), e.getMessage());
                    }
                }
            }

            if (inserted > 0) {
                log.info("[OKX] 本次新增 {} 根K线（请求{}根）", inserted, toSave.size());
            } else {
                log.debug("[OKX] 无新K线（{}根全部已存在）", toSave.size());
            }
            return inserted;

        } catch (Exception e) {
            log.error("[OKX] 采集异常: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 批量拉取历史 K线并写入数据库（支持跨多天，自动翻页）。
     *
     * OKX history-candles 翻页机制：
     *   - after 参数：只返回 ts < after 的数据（降序取老数据）
     *   - 每次最多 100 根，循环直到覆盖 startTimeMs
     *
     * @param targetSymbol  存储 symbol（如 "XAUUSDT"）
     * @param startTimeMs   起始时间（毫秒，包含）
     * @param endTimeMs     结束时间（毫秒，包含）
     * @return 实际新增的 K线数，-1 表示全部批次均失败
     */
    public int fetchHistoricalKlines(String targetSymbol, long startTimeMs, long endTimeMs) {
        log.info("[OKX] 开始历史补缺: {} → {}",
                LocalDateTime.ofInstant(Instant.ofEpochMilli(startTimeMs), ZoneId.systemDefault()),
                LocalDateTime.ofInstant(Instant.ofEpochMilli(endTimeMs), ZoneId.systemDefault()));

        int totalInserted = 0;
        int batchCount = 0;
        // 从结束时间向前翻页，after 初始 = endTimeMs + 1ms（取 ≤ endTimeMs 的数据）
        long afterMs = endTimeMs + 1;
        final int BATCH_SIZE = 100;

        while (afterMs > startTimeMs) {
            batchCount++;
            String url = String.format(
                    "%s/api/v5/market/history-candles?instId=%s&bar=5m&limit=%d&after=%d",
                    OKX_BASE_URL, OKX_INST_ID, BATCH_SIZE, afterMs);

            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("[OKX] 第{}批请求失败: HTTP {}", batchCount, response.code());
                    break;
                }

                JsonNode root = objectMapper.readTree(response.body().string());
                if (!"0".equals(root.path("code").asText())) {
                    log.warn("[OKX] 第{}批 API 错误: {}", batchCount, root.path("msg").asText());
                    break;
                }

                JsonNode data = root.path("data");
                if (!data.isArray() || data.size() == 0) {
                    log.info("[OKX] 第{}批返回空数据，已到达数据边界", batchCount);
                    break;
                }

                int batchInserted = 0;
                // 本批最早时间戳（用于向前翻页），初始 Long.MAX_VALUE 表示未更新
                long oldestTsInBatch = Long.MAX_VALUE;

                for (JsonNode item : data) {
                    long ts = item.get(0).asLong();

                    // 更新最早时间戳（不管是否在 startTimeMs 范围内，都要推进翻页指针）
                    if (ts < oldestTsInBatch) oldestTsInBatch = ts;

                    if (ts < startTimeMs) continue; // 超出目标范围，不存库但仍推进翻页指针

                    String confirm = item.size() > 8 ? item.get(8).asText() : "1";
                    // 10分钟内且未确认的跳过（当前正在形成中的K线）
                    if (!"1".equals(confirm) && (System.currentTimeMillis() - ts) < 10 * 60 * 1000L) {
                        continue;
                    }

                    Kline kline = new Kline();
                    kline.setSymbol(targetSymbol);
                    kline.setInterval("5m");
                    kline.setTimestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()));
                    kline.setOpenPrice(new BigDecimal(item.get(1).asText()));
                    kline.setHighPrice(new BigDecimal(item.get(2).asText()));
                    kline.setLowPrice(new BigDecimal(item.get(3).asText()));
                    kline.setClosePrice(new BigDecimal(item.get(4).asText()));
                    kline.setVolume(new BigDecimal(item.get(5).asText()));
                    kline.setAmount(new BigDecimal(item.get(7).asText()));
                    kline.setCreateTime(LocalDateTime.now());
                    kline.setUpdateTime(LocalDateTime.now());

                    try {
                        klineMapper.insert(kline);
                        batchInserted++;
                    } catch (Exception e) {
                        if (e.getMessage() == null || !e.getMessage().contains("Duplicate entry")) {
                            log.warn("[OKX] 插入失败: {} - {}", kline.getTimestamp(), e.getMessage());
                        }
                    }
                }

                totalInserted += batchInserted;
                log.info("[OKX] 第{}批: 获取{}根, 新增{}根, 最早时间={}, 累计新增={}",
                        batchCount, data.size(), batchInserted,
                        oldestTsInBatch != Long.MAX_VALUE
                                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(oldestTsInBatch), ZoneId.systemDefault())
                                : "N/A",
                        totalInserted);

                if (oldestTsInBatch == Long.MAX_VALUE || oldestTsInBatch <= startTimeMs) {
                    // 已超出目标范围，停止
                    break;
                }

                // 下次请求从本批最早时间戳向前（after 是开区间：ts < after）
                afterMs = oldestTsInBatch;

                if (data.size() < BATCH_SIZE) {
                    log.info("[OKX] 本批数据不足{}根，已到达OKX边界", BATCH_SIZE);
                    break;
                }

                // 限速：避免请求过快
                Thread.sleep(200);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[OKX] 第{}批异常: {}", batchCount, e.getMessage());
                break;
            }
        }

        log.info("[OKX] 历史补缺完成，共{}批，新增{}根K线", batchCount, totalInserted);
        return totalInserted;
    }

    /**
     * 快捷方法：补缺最近 N 天的数据
     */
    public int fetchRecentDays(String targetSymbol, int days) {
        long endTimeMs = System.currentTimeMillis();
        long startTimeMs = endTimeMs - (long) days * 24 * 60 * 60 * 1000;
        return fetchHistoricalKlines(targetSymbol, startTimeMs, endTimeMs);
    }

    /**
     * 补缺：从 afterTimestamp 之后补抓缺失的 K线（最多 100 根）。
     * 用于服务启动时填补短暂空白（<8小时）。
     *
     * @param targetSymbol     存储 symbol
     * @param afterTimestampMs 此时间戳之后的K线（毫秒，不含）
     * @param maxCount         最多补缺根数（建议不超过 100）
     * @return 实际补缺数量，-1 表示请求失败
     */
    public int repairGap(String targetSymbol, long afterTimestampMs, int maxCount) {
        long endTimeMs = System.currentTimeMillis();
        return fetchHistoricalKlines(targetSymbol, afterTimestampMs + 1, endTimeMs);
    }
}
