package com.taobao.arthas.grpc.server.handler.annotation;/**
 * @author: 風楪
 * @date: 2024/9/6 01:57
 */

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author: FengYe
 * @date: 2024/9/6 01:57
 * @description: GrpcMethod
 */
@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
public @interface GrpcMethod {
    String value() default "";
    boolean stream() default false;
}
