package com.github.monkeywie.proxyee;

import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptInitializer;
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptPipeline;
import com.github.monkeywie.proxyee.intercept.WebSocketDataIntercept;
import com.github.monkeywie.proxyee.server.HttpProxyServer;
import com.github.monkeywie.proxyee.server.HttpProxyServerConfig;
import com.github.monkeywie.proxyee.server.accept.DomainHttpProxyMitmMatcher;

import java.util.Arrays;

/**
 * @Author LiWei
 * @Description
 * @Date 2019/9/23 17:30
 */
public class HttpProxyServerApp {
    public static void main(String[] args) {
        System.out.println("start proxy server");
        int port = 9999;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        HttpProxyServerConfig config = new HttpProxyServerConfig();
        config.setHandleSsl(true);
//        config.setMitmMatcher(new DomainHttpProxyMitmMatcher(Arrays.asList("www.milan118.com:6001","gateway.qiandashiapp.com:18039","wsproxy.etjn2jv7.com:443","wsproxy.qiandashiapp.com:18039")));
//        new HttpProxyServer().serverConfig(config).start(port);
//         配置拦截器
        HttpProxyInterceptInitializer interceptInitializer = new HttpProxyInterceptInitializer() {
            @Override
            public void init(HttpProxyInterceptPipeline pipeline) {
                // 添加 WebSocket 数据监控
                pipeline.addLast(new WebSocketDataIntercept());
            }
        };// 配置拦截器
        new HttpProxyServer().serverConfig(config).proxyInterceptInitializer(interceptInitializer).start(port);
    }
}
