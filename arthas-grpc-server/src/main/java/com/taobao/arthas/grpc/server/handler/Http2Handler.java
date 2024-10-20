package com.taobao.arthas.grpc.server.handler;


import arthas.grpc.common.ArthasGrpc;
import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http2.*;
import io.netty.util.concurrent.EventExecutorGroup;

import java.io.*;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author: FengYe
 * @date: 2024/7/7 下午9:58
 * @description: Http2Handler
 */
public class Http2Handler extends SimpleChannelInboundHandler<Http2Frame> {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass().getName());

    private GrpcDispatcher grpcDispatcher;

    private final EventExecutorGroup executorGroup = new NioEventLoopGroup();

    /**
     * 暂存收到的所有请求的数据
     */
    private ConcurrentHashMap<Integer, GrpcRequest> dataBuffer = new ConcurrentHashMap<>();

    private static final String HEADER_PATH = ":path";

    public Http2Handler(GrpcDispatcher grpcDispatcher) {
        this.grpcDispatcher = grpcDispatcher;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        super.channelRead(ctx, msg);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Http2Frame frame) throws IOException {
        if (frame instanceof Http2HeadersFrame) {
            handleGrpcRequest((Http2HeadersFrame) frame, ctx);
        } else if (frame instanceof Http2DataFrame) {
            handleGrpcData((Http2DataFrame) frame, ctx);
        } else if (frame instanceof Http2ResetFrame) {
            handleResetStream((Http2ResetFrame) frame, ctx);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    private void handleGrpcRequest(Http2HeadersFrame headersFrame, ChannelHandlerContext ctx) {
        int id = headersFrame.stream().id();
        String path = headersFrame.headers().get(HEADER_PATH).toString();
        // 去掉前面的斜杠，然后按斜杠分割
        String[] parts = path.substring(1).split("/");
        GrpcRequest grpcRequest = new GrpcRequest(headersFrame.stream().id(), parts[0], parts[1]);
        grpcRequest.setHeaders(headersFrame.headers());
        GrpcDispatcher.checkGrpcStream(grpcRequest);
        dataBuffer.put(id, grpcRequest);
        System.out.println("Received headers: " + headersFrame.headers());
    }

    private void handleGrpcData(Http2DataFrame dataFrame, ChannelHandlerContext ctx) throws IOException {
        int streamId = dataFrame.stream().id();
        GrpcRequest grpcRequest = dataBuffer.get(streamId);
        ByteBuf content = dataFrame.content();
        grpcRequest.writeData(content);

        executorGroup.execute(() -> {
            if (grpcRequest.isStream()) {
                // 流式调用，即刻响应
                try {
                    GrpcResponse response = new GrpcResponse();
                    byte[] bytes = grpcRequest.readData();
                    while (bytes != null) {
                        response = grpcDispatcher.execute(grpcRequest.getService(), grpcRequest.getMethod(), bytes);

                        // 针对第一个响应发送 header
                        if (grpcRequest.isStreamFirstData()) {
                            ctx.writeAndFlush(new DefaultHttp2HeadersFrame(response.getEndHeader()).stream(dataFrame.stream()));
                            grpcRequest.setStreamFirstData(false);
                        }
                        ctx.writeAndFlush(new DefaultHttp2DataFrame(response.getResponseData()).stream(dataFrame.stream()));

                        bytes = grpcRequest.readData();
                    }

                    grpcRequest.clearData();

                    if (dataFrame.isEndStream()) {
                        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(response.getEndStreamHeader(), true).stream(dataFrame.stream()));
                    }
                } catch (Throwable e) {
                    processError(ctx, e, dataFrame.stream());
                }
            } else {
                // 非流式调用，等到 endStream 再响应
                if (dataFrame.isEndStream()) {
                    try {
                        GrpcResponse response = grpcDispatcher.execute(grpcRequest);
                        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(response.getEndHeader()).stream(dataFrame.stream()));
                        ctx.writeAndFlush(new DefaultHttp2DataFrame(response.getResponseData()).stream(dataFrame.stream()));
                        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(response.getEndStreamHeader(), true).stream(dataFrame.stream()));
                    } catch (Throwable e) {
                        processError(ctx, e, dataFrame.stream());
                    }
                }
            }
        });
    }

    private void handleResetStream(Http2ResetFrame resetFrame, ChannelHandlerContext ctx) {
        int id = resetFrame.stream().id();
        System.out.println("handleResetStream");
        dataBuffer.remove(id);
    }

    private void processError(ChannelHandlerContext ctx, Throwable e, Http2FrameStream stream) {
        GrpcResponse response = new GrpcResponse();
        ArthasGrpc.ErrorRes.Builder builder = ArthasGrpc.ErrorRes.newBuilder();
        ArthasGrpc.ErrorRes errorRes = builder.setErrorMsg(e.getMessage()).build();
        response.setClazz(ArthasGrpc.ErrorRes.class);
        response.writeResponseData(errorRes);
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(response.getEndHeader()).stream(stream));
        ctx.writeAndFlush(new DefaultHttp2DataFrame(response.getResponseData()).stream(stream));
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(response.getEndStreamHeader(), true).stream(stream));
    }
}