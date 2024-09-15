package com.taobao.arthas.grpc.server.service.impl;/**
 * @author: 風楪
 * @date: 2024/6/30 下午11:43
 */

import com.taobao.arthas.grpc.server.handler.annotation.GrpcMethod;
import com.taobao.arthas.grpc.server.handler.annotation.GrpcService;
import com.taobao.arthas.grpc.server.service.ArthasSampleService;
import com.taobao.arthas.grpc.server.service.req.ArthasSampleRequest;
import com.taobao.arthas.grpc.server.service.res.ArthasSampleResponse;

/**
 * @author: FengYe
 * @date: 2024/6/30 下午11:43
 * @description: ArthasSampleServiceImpl
 */
@GrpcService("arthasSample.ArthasTempService")
public class ArthasSampleServiceImpl implements ArthasSampleService {

    @Override
    @GrpcMethod("trace")
    public ArthasSampleResponse trace(ArthasSampleRequest command) {
        ArthasSampleResponse arthasSampleResponse = new ArthasSampleResponse();
        arthasSampleResponse.setMessage("trace");
        return arthasSampleResponse;
    }

    @Override
    @GrpcMethod(value = "watch", stream = true)
    public ArthasSampleResponse watch(ArthasSampleRequest command) {
        String name = command.getName();
        ArthasSampleResponse arthasSampleResponse = new ArthasSampleResponse();
        arthasSampleResponse.setMessage(name);
        return arthasSampleResponse;
    }
}