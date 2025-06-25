package com.github.monkeywie.proxyee.intercept;

import com.github.monkeywie.proxyee.util.DecryptUtil;
import com.github.monkeywie.proxyee.util.ProtoUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * WebSocket 数据拦截器
 * 用于打印 WSS 建立后的请求和返回数据
 */
public class WebSocketDataIntercept extends HttpProxyIntercept {

    private boolean isWebSocketUpgraded = false;
    private String host = "";
    private String url = "";
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private static final String HAND_SHAKE = "/stream/ws/v1/handshake";

    // 暂时关闭丢弃功能
    private static final boolean DROP_UNDECRYPTED_PACKETS = false;

    @Override
    public void afterResponse(Channel clientChannel, Channel proxyChannel, HttpResponse httpResponse,
                              HttpProxyInterceptPipeline pipeline) throws Exception {

        // 检测 WebSocket 升级响应
        if (HttpHeaderValues.WEBSOCKET.toString().equals(httpResponse.headers().get(HttpHeaderNames.UPGRADE))) {
            isWebSocketUpgraded = true;
            ProtoUtil.RequestProto requestProto = pipeline.getRequestProto();
            host = requestProto.getHost() + ":" + requestProto.getPort(); // 保存host信息

            // 构建完整的WebSocket URL
            String protocol = requestProto.getSsl() ? "wss" : "ws";
            String port = (requestProto.getPort() == 80 && !requestProto.getSsl()) ||
                    (requestProto.getPort() == 443 && requestProto.getSsl()) ? "" : ":" + requestProto.getPort();
            url = pipeline.getHttpRequest() != null ? pipeline.getHttpRequest().uri() : "/";

            System.out.println("=== WebSocket 连接建立 ===");
            System.out.println("Host: " + host);
            System.out.println("URL: " + url);
            System.out.println("SSL: " + requestProto.getSsl());
            System.out.println("========================");

            // 先正常处理响应
            pipeline.afterResponse(clientChannel, proxyChannel, httpResponse);

            // 延迟添加拦截器，等待 httpCodec 被移除并确保处理顺序正确
            clientChannel.eventLoop().schedule(() -> {
                try {
                    addRawDataHandler(clientChannel, "客户端->代理");
                    addRawDataHandler(proxyChannel, "服务端->代理");
                    System.out.println("=== WebSocket 原始数据监听器已添加 ===");
                } catch (Exception e) {
                    System.err.println("添加原始数据监听器失败: " + e.getMessage());
                    e.printStackTrace();
                }
            }, 500, java.util.concurrent.TimeUnit.MILLISECONDS);

            return;
        }

        // 继续处理其他响应
        pipeline.afterResponse(clientChannel, proxyChannel, httpResponse);
    }

    private void addRawDataHandler(Channel channel, String direction) {
        try {
            String handlerName = "rawDataLogger_" + direction.hashCode();

            // 检查是否已经添加过
            if (channel.pipeline().get(handlerName) != null) {
                System.out.println("原始数据监听器已存在: " + direction);
                return;
            }

//            System.out.println("添加原始数据监听器到 " + direction);
//            System.out.println("当前 pipeline: " + channel.pipeline().names());
//            System.out.println("Channel 类型: " + channel.getClass().getSimpleName());
//            System.out.println("Channel 是否活跃: " + channel.isActive());

            // 创建原始数据处理器（只处理入站数据，不处理出站数据）
            io.netty.channel.ChannelDuplexHandler rawDataHandler = new io.netty.channel.ChannelDuplexHandler() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    // 只在 WebSocket 升级后拦截数据
                    if (isWebSocketUpgraded && msg instanceof ByteBuf) {
                        ByteBuf byteBuf = (ByteBuf) msg;
                        if (byteBuf.readableBytes() > 0) {
                            // 检查是否需要丢弃这个数据包
                            if (shouldDropPacket(byteBuf)) {
                                System.out.println("🚫 丢弃无法解密的数据包 [" + direction + "] 时间:" + dateFormat.format(new Date()) + " 大小:" + byteBuf.readableBytes() + "字节");
                                // 释放 ByteBuf 但不传递
                                byteBuf.release();
                                return; // 不调用 ctx.fireChannelRead(msg)
                            } else {
                                printWebSocketData(direction, byteBuf);
                            }
                        }
                    } else if (isWebSocketUpgraded) {
                        System.out.println("⚠️ [" + direction + "] WebSocket已升级但收到非ByteBuf数据: " + msg.getClass().getSimpleName());
                    }
                    // 继续传递消息
                    ctx.fireChannelRead(msg);
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                    System.out.println("=== WebSocket 连接断开 [" + direction + "] ===");
                    ctx.fireChannelInactive();
                }

                @Override
                public void write(ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) throws Exception {
                    // 不处理出站数据，直接传递
                    ctx.write(msg, promise);
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                    System.err.println("原始数据处理器异常 [" + direction + "]: " + cause.getMessage());
                    ctx.fireExceptionCaught(cause);
                }
            };

            // 检查 httpCodec 是否还在，如果在就等待
            if (channel.pipeline().get("httpCodec") != null) {
                System.out.println("httpCodec 仍在 pipeline 中，等待其被移除...");
                // 再等待一段时间
                channel.eventLoop().schedule(() -> addRawDataHandler(channel, direction),
                        500, java.util.concurrent.TimeUnit.MILLISECONDS);
                return;
            }

            // 找到合适的位置插入（在 SSL 处理器之后，业务处理器之前）
            String insertAfter = null;
            if (channel.pipeline().get("sslHandle") != null) {
                insertAfter = "sslHandle";
            } else if (channel.pipeline().get("SslHandler#0") != null) {
                insertAfter = "SslHandler#0";
            }

            if (insertAfter != null) {
                channel.pipeline().addAfter(insertAfter, handlerName, rawDataHandler);
            } else {
                // 如果没有 SSL 处理器，添加到最前面
                channel.pipeline().addFirst(handlerName, rawDataHandler);
            }

//            System.out.println("原始数据监听器添加成功: " + direction);
//            System.out.println("更新后 pipeline: " + channel.pipeline().names());

        } catch (Exception e) {
            System.err.println("添加原始数据监听器失败 [" + direction + "]: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void printWebSocketData(String direction, ByteBuf byteBuf) {
        try {
            // 保存当前读取位置
            int readerIndex = byteBuf.readerIndex();

            // 读取数据（不改变 ByteBuf 的读取位置）
            byte[] data = new byte[Math.min(byteBuf.readableBytes(), 1024)]; // 限制最大1024字节
            byteBuf.getBytes(readerIndex, data);

            // 检查WebSocket帧类型
            int opcode = data.length > 0 ? (data[0] & 0x0F) : -1;
            String frameType = getOpcodeDescription(opcode);

            // 提取WebSocket payload数据
            byte[] payload = extractWebSocketPayload(data);
            if (payload != null && payload.length > 0) {
                try {
                    // 特殊处理握手URL
                    if (url.contains(HAND_SHAKE)) {
                        String text = new String(payload, StandardCharsets.UTF_8);
                        System.out.println("📤  Websocket-handShark消息:[" + direction + "]" + " 时间:" + dateFormat.format(new Date()) + " 内容: " + text);
                        return;
                    }

                    // 先尝试使用原来的密钥
                    String base64String = DecryptUtil.getBase64String(payload);
                    byte[] aesDecrypt = DecryptUtil.aesDecrypt(base64String, DecryptUtil.DEFAULT_KEY.getBytes(StandardCharsets.UTF_8), "CBC");
                    String jsonData = DecryptUtil.unzip(aesDecrypt);

                    System.out.println("📤  Websocket消息-decryptWsData:[" + direction + "]" + " 时间:" + dateFormat.format(new Date()) + " Host:" + host + " URL:" + url + " 内容: " + jsonData);
                } catch (Exception e1) {
                    // 第一个密钥失败，尝试第二个密钥
                    try {
                        byte[] aesDecrypt = DecryptUtil.aesDecrypt(payload, DecryptUtil.DEFAULT_KEY.getBytes(StandardCharsets.UTF_8),"CBC");
                        String jsonData = DecryptUtil.unzip(aesDecrypt);
                        System.out.println("📤  Websocket消息-aesDecrypt:[" + direction + "]" + " 时间:" + dateFormat.format(new Date()) + " Host:" + host + " URL:" + url + " 内容: " + jsonData);
                    } catch (Exception e2) {
                        // 两个密钥都失败，进行详细分析
                            // 直接转换为base64并打印
                        String base64Data = java.util.Base64.getEncoder().encodeToString(payload);
                        System.out.println("📦  Base64数据 [" + direction + "] 时间:" + dateFormat.format(new Date()) + " Host:" + host + " URL:" + url + " Base64: " + base64Data);
                    }
                }
            } else {
                System.out.println("⚠️  无法提取WebSocket payload数据 [" + direction + "] 帧类型: " + frameType);
            }

        } catch (Exception e) {
            System.err.println("解析 WebSocket 数据时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 分析解密失败的数据包
     */
    private void analyzeFailedPayload(String direction, byte[] payload, String frameType, Exception e1, Exception e2) {
        System.out.println("🔍 两个密钥都解密失败 [" + direction + "] 时间:" + dateFormat.format(new Date()) + " 帧类型:" + frameType + " 大小:" + payload.length + "字节");
        System.out.println("🔍 错误1: " + e1.getMessage());
        System.out.println("🔍 错误2: " + e2.getMessage());

        // 检查数据特征
        System.out.println(" 数据特征分析:");
        System.out.println("  - 数据长度: " + payload.length + " 字节");
        System.out.println("  - 是否为16的倍数: " + (payload.length % 16 == 0));
        System.out.println("  - 前16字节: " + bytesToHex(payload, 16));

        // 检查是否是压缩数据
        if (payload.length >= 2) {
            int firstByte = payload[0] & 0xFF;
            int secondByte = payload[1] & 0xFF;
            System.out.println("  - 前两个字节: " + String.format("%02X %02X", firstByte, secondByte));

            // 检查GZIP魔数
            if (firstByte == 0x1F && secondByte == 0x8B) {
                System.out.println("  - 检测到GZIP压缩数据");
                try {
                    String unzipped = DecryptUtil.unzip(payload);
                    System.out.println("  - GZIP解压结果: " + unzipped);
                    return;
                } catch (Exception ex) {
                    System.out.println("  - GZIP解压失败: " + ex.getMessage());
                }
            }

            // 检查ZIP魔数
            if (payload.length >= 4 && firstByte == 0x50 && secondByte == 0x4B) {
                System.out.println("  - 检测到ZIP压缩数据");
            }

            // 检查zlib魔数
            if (secondByte == 0x01 || secondByte == 0x9C || secondByte == 0xDA) {
                System.out.println("  - 检测到zlib压缩数据");
            }

            // 检查是否是Protobuf或其他二进制格式
            if (firstByte == 0x99 && secondByte == 0x70) {
                System.out.println("  - 检测到可能的Protobuf或自定义二进制格式");
                analyzeBinaryFormat(payload);
                return;
            }
        }

        // 尝试多种编码方式
        System.out.println("  - 尝试多种编码方式:");

        boolean foundReadableText = false;

        // UTF-8
        try {
            String utf8Text = new String(payload, StandardCharsets.UTF_8);
            System.out.println("  - UTF-8解码结果: " + utf8Text);
            if (isLikelyText(utf8Text)) {
                System.out.println("  - UTF-8解码成功，发现可读文本!");
                foundReadableText = true;
            }
        } catch (Exception ex) {
            System.out.println("  - UTF-8解码失败: " + ex.getMessage());
        }

        // GBK
        try {
            String gbkText = new String(payload, "GBK");
            System.out.println("  - GBK解码结果: " + gbkText);
            if (isLikelyText(gbkText)) {
                System.out.println("  - GBK解码成功，发现可读文本!");
                foundReadableText = true;
            }
        } catch (Exception ex) {
            System.out.println("  - GBK解码失败: " + ex.getMessage());
        }

        // GB2312
        try {
            String gb2312Text = new String(payload, "GB2312");
            System.out.println("  - GB2312解码结果: " + gb2312Text);
            if (isLikelyText(gb2312Text)) {
                System.out.println("  - GB2312解码成功，发现可读文本!");
                foundReadableText = true;
            }
        } catch (Exception ex) {
            System.out.println("  - GB2312解码失败: " + ex.getMessage());
        }

        // Big5
        try {
            String big5Text = new String(payload, "Big5");
            System.out.println("  - Big5解码结果: " + big5Text);
            if (isLikelyText(big5Text)) {
                System.out.println("  - Big5解码成功，发现可读文本!");
                foundReadableText = true;
            }
        } catch (Exception ex) {
            System.out.println("  - Big5解码失败: " + ex.getMessage());
        }

        // ISO-8859-1
        try {
            String isoText = new String(payload, "ISO-8859-1");
            System.out.println("  - ISO-8859-1解码结果: " + isoText);
            if (isLikelyText(isoText)) {
                System.out.println("  - ISO-8859-1解码成功，发现可读文本!");
                foundReadableText = true;
            }
        } catch (Exception ex) {
            System.out.println("  - ISO-8859-1解码失败: " + ex.getMessage());
        }

        // UTF-16
        try {
            String utf16Text = new String(payload, "UTF-16");
            System.out.println("  - UTF-16解码结果: " + utf16Text);
            if (isLikelyText(utf16Text)) {
                System.out.println("  - UTF-16解码成功，发现可读文本!");
                foundReadableText = true;
            }
        } catch (Exception ex) {
            System.out.println("  - UTF-16解码失败: " + ex.getMessage());
        }

        // UTF-16LE
        try {
            String utf16leText = new String(payload, "UTF-16LE");
            System.out.println("  - UTF-16LE解码结果: " + utf16leText);
            if (isLikelyText(utf16leText)) {
                System.out.println("  - UTF-16LE解码成功，发现可读文本!");
                foundReadableText = true;
            }
        } catch (Exception ex) {
            System.out.println("  - UTF-16LE解码失败: " + ex.getMessage());
        }

        // UTF-16BE
        try {
            String utf16beText = new String(payload, "UTF-16BE");
            System.out.println("  - UTF-16BE解码结果: " + utf16beText);
            if (isLikelyText(utf16beText)) {
                System.out.println("  - UTF-16BE解码成功，发现可读文本!");
                foundReadableText = true;
            }
        } catch (Exception ex) {
            System.out.println("  - UTF-16BE解码失败: " + ex.getMessage());
        }

        // 检查是否是Base64编码的字符串
        try {
            String base64String = new String(payload, StandardCharsets.UTF_8);
            if (base64String.matches("^[A-Za-z0-9+/]*={0,2}$")) {
                System.out.println("  - 检测到Base64编码字符串: " + base64String);
                // 尝试解码Base64
                byte[] decoded = java.util.Base64.getDecoder().decode(base64String);
                System.out.println("  - Base64解码后长度: " + decoded.length + " 字节");
                System.out.println("  - Base64解码后前16字节: " + bytesToHex(decoded, 16));
                return;
            }
        } catch (Exception ex) {
            // 忽略Base64解码失败
        }

        // 检查是否是其他加密方式
        if (payload.length % 16 == 0) {
            System.out.println("  - 数据长度是16的倍数，可能是其他加密方式");
            System.out.println("  - 尝试分析加密特征:");

            // 检查是否有重复模式
            boolean hasRepeatingPattern = false;
            for (int i = 0; i < payload.length - 16; i += 16) {
                for (int j = i + 16; j < payload.length - 16; j += 16) {
                    boolean match = true;
                    for (int k = 0; k < 16; k++) {
                        if (payload[i + k] != payload[j + k]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        hasRepeatingPattern = true;
                        System.out.println("  - 发现重复的16字节块: 位置 " + i + " 和 " + j);
                        break;
                    }
                }
                if (hasRepeatingPattern) break;
            }

            if (!hasRepeatingPattern) {
                System.out.println("  - 没有发现明显的重复模式");
            }
        }

        if (!foundReadableText) {
            System.out.println("  - 所有编码方式都未能产生可读文本，可能是加密数据");
        }

        // 最后输出base64
        System.out.println("  - 输出Base64:");
        handleDecryptFailure(direction, payload, host, url);
    }

    /**
     * 分析二进制格式数据
     */
    private void analyzeBinaryFormat(byte[] payload) {
        System.out.println("  - 二进制格式分析:");

        // 检查前几个字节的模式
        if (payload.length >= 8) {
            System.out.println("  - 前8字节: " + bytesToHex(payload, 8));

            // 检查是否有长度字段
            int possibleLength1 = ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
            int possibleLength2 = ((payload[4] & 0xFF) << 8) | (payload[5] & 0xFF);
            int possibleLength3 = ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);

            System.out.println("  - 可能的长度字段1 (字节2-3): " + possibleLength1);
            System.out.println("  - 可能的长度字段2 (字节4-5): " + possibleLength2);
            System.out.println("  - 可能的长度字段3 (字节6-7): " + possibleLength3);

            // 检查是否有JSON特征
            for (int i = 0; i < Math.min(payload.length, 100); i++) {
                if (payload[i] == '{' || payload[i] == '[' || payload[i] == '"') {
                    System.out.println("  - 在位置 " + i + " 发现JSON字符: " + (char)payload[i]);
                    // 尝试从该位置开始解析
                    try {
                        String jsonPart = new String(payload, i, Math.min(payload.length - i, 200), StandardCharsets.UTF_8);
                        System.out.println("  - 可能的JSON片段: " + jsonPart);
                    } catch (Exception ex) {
                        // 忽略
                    }
                    break;
                }
            }
        }

        // 检查数据分布
        int zeroCount = 0, printableCount = 0, controlCount = 0;
        for (int i = 0; i < Math.min(payload.length, 100); i++) {
            if (payload[i] == 0) zeroCount++;
            else if (payload[i] >= 32 && payload[i] <= 126) printableCount++;
            else controlCount++;
        }
        System.out.println("  - 数据分布 (前100字节): 零字节=" + zeroCount + ", 可打印=" + printableCount + ", 控制字符=" + controlCount);
    }

    /**
     * 处理解密失败的情况
     */
    private void handleDecryptFailure(String direction, byte[] payload, String host, String url) {
        // 直接转换为base64并打印
        String base64Data = java.util.Base64.getEncoder().encodeToString(payload);
        System.out.println("📦  Base64数据 [" + direction + "] 时间:" + dateFormat.format(new Date()) + " Host:" + host + " URL:" + url + " Base64: " + base64Data);
    }

    /**
     * 判断字符串是否像可读文本
     */
    private boolean isLikelyText(String text) {
        if (text == null || text.isEmpty()) return false;

        int totalChars = text.length();
        int controlChars = 0;
        int printableChars = 0;

        for (char c : text.toCharArray()) {
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                controlChars++;
            } else if (Character.isLetterOrDigit(c) || Character.isWhitespace(c) || isPunctuation(c) || isChineseChar(c)) {
                printableChars++;
            }
        }

        // 如果控制字符超过20%，很可能是二进制数据
        return (double) controlChars / totalChars < 0.2 && printableChars > totalChars * 0.5;
    }

    /**
     * 判断是否是标点符号
     */
    private boolean isPunctuation(char c) {
        String punctuation = "!@#$%^&*()_+-=[]{}|;':\";,./<>?~`，。！？；：\"\"''（）【】《》";
        return punctuation.indexOf(c) >= 0;
    }

    /**
     * 判断是否是中文字符
     */
    private boolean isChineseChar(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /**
     * 检查是否可能是压缩数据
     */
    private boolean isLikelyCompressed(byte[] data) {
        if (data.length < 2) return false;

        // 检查GZIP魔数
        if (data.length >= 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
            return true;
        }

        // 检查ZIP魔数
        if (data.length >= 4 && (data[0] & 0xFF) == 0x50 && (data[1] & 0xFF) == 0x4B) {
            return true;
        }

        // 检查zlib魔数
        if (data.length >= 2) {
            int b1 = data[0] & 0xFF;
            int b2 = data[1] & 0xFF;
            if ((b1 == 0x78 && (b2 == 0x01 || b2 == 0x9C || b2 == 0xDA))) {
                return true;
            }
        }

        return false;
    }

    /**
     * 提取WebSocket帧的payload数据
     */
    private byte[] extractWebSocketPayload(byte[] data) {
        try {
            if (data.length < 2) return null;

            byte secondByte = data[1];
            boolean masked = (secondByte & 0x80) != 0;
            int payloadLength = secondByte & 0x7F;

            int offset = 2;

            // 处理扩展 payload length
            if (payloadLength == 126) {
                if (data.length < 4) return null;
                payloadLength = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                offset = 4;
            } else if (payloadLength == 127) {
                if (data.length < 10) return null;
                // 简化处理，只取低32位
                payloadLength = ((data[6] & 0xFF) << 24) | ((data[7] & 0xFF) << 16) |
                        ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);
                offset = 10;
            }

            // 处理 mask
            byte[] maskKey = null;
            if (masked) {
                if (data.length < offset + 4) return null;
                maskKey = new byte[4];
                System.arraycopy(data, offset, maskKey, 0, 4);
                offset += 4;
            }

            // 提取 payload
            if (data.length < offset + payloadLength) {
                payloadLength = data.length - offset; // 调整长度
            }

            if (payloadLength <= 0) return null;

            byte[] payload = new byte[payloadLength];
            System.arraycopy(data, offset, payload, 0, payloadLength);

            // 解码（如果有 mask）
            if (masked && maskKey != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= maskKey[i % 4];
                }
            }

            return payload;

        } catch (Exception e) {
            System.err.println("提取WebSocket payload时出错: " + e.getMessage());
            return null;
        }
    }

    private void parseWebSocketFrame(byte[] data) {
        if (data.length < 2) {
            System.out.println("数据太短，无法解析 WebSocket 帧");
            return;
        }

        byte firstByte = data[0];
        byte secondByte = data[1];

        boolean fin = (firstByte & 0x80) != 0;
        int opcode = firstByte & 0x0F;
        boolean masked = (secondByte & 0x80) != 0;
        int payloadLength = secondByte & 0x7F;

        System.out.println("WebSocket 帧信息:");
        System.out.println("  FIN: " + fin);
        System.out.println("  Opcode: " + opcode + " (" + getOpcodeDescription(opcode) + ")");
        System.out.println("  Masked: " + masked);
        System.out.println("  Payload Length: " + payloadLength);
    }

    private String getOpcodeDescription(int opcode) {
        switch (opcode) {
            case 0x0:
                return "Continuation Frame";
            case 0x1:
                return "Text Frame";
            case 0x2:
                return "Binary Frame";
            case 0x8:
                return "Connection Close";
            case 0x9:
                return "Ping";
            case 0xA:
                return "Pong";
            default:
                return "Unknown";
        }
    }

    private String bytesToHex(byte[] bytes, int maxLength) {
        StringBuilder result = new StringBuilder();
        int length = Math.min(bytes.length, maxLength);
        for (int i = 0; i < length; i++) {
            if (i > 0 && i % 16 == 0) {
                result.append("\n");
            } else if (i > 0 && i % 8 == 0) {
                result.append("  ");
            } else if (i > 0) {
                result.append(" ");
            }
            result.append(String.format("%02X", bytes[i] & 0xFF));
        }
        if (bytes.length > maxLength) {
            result.append("\n... (还有 ").append(bytes.length - maxLength).append(" 字节)");
        }
        return result.toString();
    }

    /**
     * 检查是否应该丢弃这个数据包
     */
    private boolean shouldDropPacket(ByteBuf byteBuf) {
        if (!DROP_UNDECRYPTED_PACKETS) {
            return false; // 如果开关关闭，不丢弃任何包
        }

        try {
            // 保存当前读取位置
            int readerIndex = byteBuf.readerIndex();

            // 读取数据（不改变 ByteBuf 的读取位置）
            byte[] data = new byte[Math.min(byteBuf.readableBytes(), 1024)];
            byteBuf.getBytes(readerIndex, data);

            // 检查WebSocket帧类型
            int opcode = data.length > 0 ? (data[0] & 0x0F) : -1;

            // 不丢弃控制帧（Ping, Pong, Close等）
            // if (opcode == 0x8 || opcode == 0x9 || opcode == 0xA) {
            //     System.out.println("🔒 保留控制帧 [" + getOpcodeDescription(opcode) + "]");
            //     return false;
            // }

            // 提取WebSocket payload数据
            byte[] payload = extractWebSocketPayload(data);
            if (payload != null && payload.length > 0) {
                try {
                    // 特殊处理握手URL - 不丢弃握手数据
                    if (url.contains(HAND_SHAKE)) {
                        return false;
                    }

                    // 尝试解密
                    String base64String = DecryptUtil.getBase64String(payload);
                    byte[] aesDecrypt = DecryptUtil.aesDecrypt(base64String, DecryptUtil.DEFAULT_KEY.getBytes(StandardCharsets.UTF_8), "CBC");
                    String jsonData = DecryptUtil.unzip(aesDecrypt);
                
                    // 解密成功，不丢弃
                    return false;
                } catch (Exception e) {
                    // 解密失败，记录详细信息
                    System.out.println("🔍 解密失败的数据包 [" + getOpcodeDescription(opcode) + "] 大小:" + payload.length + "字节");
                    return true; // 丢弃
                }
            }
            
            return false; // 无法提取payload，不丢弃
        } catch (Exception e) {
            return false; // 出错时不丢弃
        }
    }
} 