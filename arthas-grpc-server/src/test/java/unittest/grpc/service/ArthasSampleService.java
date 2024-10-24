package unittest.grpc.service;

import arthas.grpc.unittest.ArthasUnittest;
import arthas.grpc.unittest.ArthasUnittest.ArthasUnittestRequest;
import arthas.grpc.unittest.ArthasUnittest.ArthasUnittestResponse;
import com.taobao.arthas.grpc.server.handler.GrpcRequest;
import com.taobao.arthas.grpc.server.handler.GrpcResponse;
import com.taobao.arthas.grpc.server.handler.StreamObserver;

/**
 * @author: FengYe
 * @date: 2024/6/30 下午11:42
 * @description: ArthasSampleService
 */
public interface ArthasSampleService {
    ArthasUnittestResponse trace(ArthasUnittestRequest command);

    ArthasUnittestResponse watch(ArthasUnittestRequest command);

    ArthasUnittestResponse unaryAddSum(ArthasUnittestRequest command);

    ArthasUnittestResponse unaryGetSum(ArthasUnittestRequest command);

    StreamObserver<GrpcRequest<ArthasUnittestRequest>> clientStreamSum(StreamObserver<GrpcResponse<ArthasUnittestResponse>> observer);
}
