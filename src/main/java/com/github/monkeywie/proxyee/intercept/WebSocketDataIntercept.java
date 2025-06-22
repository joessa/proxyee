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
                    addRawDataHandler(clientChannel, "客户端->服务端");
                    addRawDataHandler(proxyChannel, "服务端->客户端");
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

            // 创建原始数据处理器（同时处理入站和出站）
            io.netty.channel.ChannelDuplexHandler rawDataHandler = new io.netty.channel.ChannelDuplexHandler() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//                    System.out.println("🔍 [" + direction + "] 收到消息: " + msg.getClass().getSimpleName() +
//                        ", WebSocket已升级: " + isWebSocketUpgraded);
//                    System.out.println("📦  Websocket消息:[" + direction + "]"+ " 时间:" + dateFormat.format(new Date()) + " Host:" + host);

                    // 只在 WebSocket 升级后拦截数据
                    if (isWebSocketUpgraded && msg instanceof ByteBuf) {
                        ByteBuf byteBuf = (ByteBuf) msg;
//                        System.out.println("🔍 [" + direction + "] ByteBuf 可读字节: " + byteBuf.readableBytes());
                        if (byteBuf.readableBytes() > 0) {
//                            System.out.println("✅ 拦截到原始数据 [" + direction + "]");
                            printWebSocketData(direction, byteBuf);
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

//                    System.out.println("📤 [" + direction + "] 出站消息: " + msg.getClass().getSimpleName() +
//                        ", WebSocket已升级: " + isWebSocketUpgraded);
//                    System.out.println("📤  Websocket消息:[" + direction + "]"+ " 时间:" + dateFormat.format(new Date()) + " Host:" + host);

                    if (isWebSocketUpgraded && msg instanceof ByteBuf) {
                        ByteBuf byteBuf = (ByteBuf) msg;
//                        System.out.println("📦 [" + direction + "] 出站 ByteBuf 可读字节: " + byteBuf.readableBytes());
                        if (byteBuf.readableBytes() > 0) {
//                            System.out.println("✅ 拦截到出站数据 [" + direction + "]");
                            printWebSocketData(direction, byteBuf);
                        }
                    }
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

                    // 尝试解密
                    String base64String = DecryptUtil.getBase64String(payload);
                    byte[] aesDecrypt = DecryptUtil.aesDecrypt(base64String, DecryptUtil.DEFAULT_KEY.getBytes(StandardCharsets.UTF_8), "CBC");
                    String jsonData = DecryptUtil.unzip(aesDecrypt);

                    System.out.println("📤  Websocket消息:[" + direction + "]" + " 时间:" + dateFormat.format(new Date()) + " Host:" + host + " URL:" + url + " 内容: " + jsonData);
                } catch (Exception e) {
                    // 解密失败，尝试多种方式处理
                    handleDecryptFailure(direction, payload, frameType, opcode);
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
     * 处理解密失败的情况
     */
    private void handleDecryptFailure(String direction, byte[] payload, String frameType, int opcode) {
        try {
            // 根据帧类型决定处理方式
            if (opcode == 0x8) { // 连接关闭帧
                String reason = payload.length >= 2 ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8) : "无原因";
                int code = payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 0;
                System.out.println("🔌  WebSocket关闭 [" + direction + "] 时间:" + dateFormat.format(new Date()) + " 关闭码:" + code + " 原因:" + reason);
                return;
            } else if (opcode == 0x9) { // Ping帧
                System.out.println("💓  WebSocket Ping [" + direction + "] 时间:" + dateFormat.format(new Date()) + " 数据:" + bytesToHex(payload, 32));
                return;
            } else if (opcode == 0xA) { // Pong帧
                System.out.println("💓  WebSocket Pong [" + direction + "] 时间:" + dateFormat.format(new Date()) + " 数据:" + bytesToHex(payload, 32));
                return;
            }

            // 尝试UTF-8解码
            String utf8Text = new String(payload, StandardCharsets.UTF_8);
            if (isLikelyText(utf8Text)) {
                System.out.println("📧  UTF-8文本 [" + direction + "] 时间:" + dateFormat.format(new Date()) + " 帧类型:" + frameType + " 内容: " + utf8Text);
                return;
            }

            // 尝试GBK解码
            String gbkText = new String(payload, "GBK");
            if (isLikelyText(gbkText)) {
                System.out.println("📧  GBK文本 [" + direction + "] 时间:" + dateFormat.format(new Date()) + " 帧类型:" + frameType + " 内容: " + gbkText);
                return;
            }

            // 检查是否可能是压缩数据
            if (isLikelyCompressed(payload)) {
                System.out.println("📦  疑似压缩数据 [" + direction + "] 时间:" + dateFormat.format(new Date()) + " 帧类型:" + frameType + " 大小:" + payload.length + "字节 HEX: " + bytesToHex(payload, 64));
                return;
            }

            // 都不像文本，输出十六进制
            System.out.println("📦  二进制数据 [" + direction + "] 时间:" + dateFormat.format(new Date()) + " 帧类型:" + frameType + " 大小:" + payload.length + "字节 HEX: " + bytesToHex(payload, 128));

        } catch (Exception ex) {
            System.out.println("📦  处理失败，显示十六进制: " + bytesToHex(payload, 64));
        }
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
} 