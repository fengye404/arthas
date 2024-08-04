package com.taobao.arthas.protobuf;/**
 * @author: 風楪
 * @date: 2024/7/17 下午9:57
 */


import com.baidu.bjf.remoting.protobuf.FieldType;
import com.baidu.bjf.remoting.protobuf.code.ClassCode;
import com.baidu.bjf.remoting.protobuf.code.CodedConstant;
import com.baidu.bjf.remoting.protobuf.utils.ClassHelper;
import com.baidu.bjf.remoting.protobuf.utils.FieldInfo;
import com.google.protobuf.WireFormat;
import com.taobao.arthas.protobuf.annotation.ProtobufEnableZigZap;
import com.taobao.arthas.protobuf.annotation.ProtobufClass;
import com.taobao.arthas.protobuf.utils.FieldUtil;
import com.taobao.arthas.protobuf.utils.MiniTemplator;
import com.taobao.arthas.service.req.ArthasSampleRequest;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author: FengYe
 * @date: 2024/7/17 下午9:57
 * @description: ProtoBufProxy
 */
public class ProtobufProxy {
    private static final String TEMPLATE_FILE = "/class_template.tpl";

    private static final Map<String, ProtobufCodec> codecCache = new ConcurrentHashMap<String, ProtobufCodec>();

    private static Class<?> clazz;

    private static MiniTemplator miniTemplator;

    private static List<ProtobufField> protobufFields;

    public ProtobufProxy(Class<?> clazz) {

    }

    public ProtobufCodec getCodecCacheSide(Class<?> clazz) {
        ProtobufCodec codec = codecCache.get(clazz.getName());
        if (codec != null) {
            return codec;
        }
        try {
            codec = create(clazz);
            codecCache.put(clazz.getName(), codec);
            return codec;
        } catch (Exception exception) {
            exception.printStackTrace();
            return null;
        }
    }

    public static ProtobufCodec create(Class<?> clazz) {
        Objects.requireNonNull(clazz);
        if (clazz.getAnnotation(ProtobufClass.class) == null) {
            throw new IllegalArgumentException("class is not annotated with @ProtobufClass");
        }
        ProtobufProxy.clazz = clazz;
        loadProtobufField();

        String path = Objects.requireNonNull(clazz.getResource(TEMPLATE_FILE)).getPath();
        try {
            miniTemplator = new MiniTemplator(path);
        } catch (Exception e) {
            throw new RuntimeException("miniTemplator init failed. " + path, e);
        }

        miniTemplator.setVariable("package", "package " + clazz.getPackage().getName() + ";");

        processImportBlock();

        miniTemplator.setVariable("className", clazz.getName() + "$$ProxyClass");
        miniTemplator.setVariable("codecClassName", ProtobufCodec.class.getName());
        miniTemplator.setVariable("targetProxyClassName", clazz.getName());
        processEncodeBlock();
        processDecodeBlock();

        return null;
    }

    private static void processImportBlock() {
        Set<String> imports = new HashSet<>();
        imports.add("java.util.*");
        imports.add("java.io.IOException");
        imports.add("java.lang.reflect.*");
//        imports.add("com.baidu.bjf.remoting.protobuf.FieldType"); // fix the class ambiguous of FieldType
//        imports.add("com.baidu.bjf.remoting.protobuf.code.*");
//        imports.add("com.baidu.bjf.remoting.protobuf.utils.*");
//        imports.add("com.baidu.bjf.remoting.protobuf.*");
        imports.add("com.google.protobuf.*");
        imports.add(clazz.getName());
        for (String pkg : imports) {
            miniTemplator.setVariable("importBlock", pkg);
            miniTemplator.addBlock("imports");
        }
    }

    private static void processEncodeBlock() {
        for (ProtobufField protobufField : protobufFields) {
            String dynamicFieldGetter = FieldUtil.getGetterDynamicString(protobufField, clazz);
            String dynamicFieldType = protobufField.isList() ? "Collection" : protobufField.getProtobufFieldType().getJavaType();
            String dynamicFieldName = FieldUtil.getDynamicFieldName(protobufField.getOrder());

            miniTemplator.setVariable("dynamicFieldType", dynamicFieldType);
            miniTemplator.setVariable("dynamicFieldName", dynamicFieldName);
            miniTemplator.setVariable("dynamicFieldGetter", dynamicFieldGetter);
            String sizeDynamicString = FieldUtil.getSizeDynamicString(protobufField);
            miniTemplator.setVariable("sizeDynamicString", sizeDynamicString);
            miniTemplator.setVariable("encodeWriteFieldValue", FieldUtil.getWriteByteDynamicString(protobufField));
            miniTemplator.addBlock("encodeFields");
        }
    }

    private static void processDecodeBlock() {
        // 初始化 list、map、enum
        StringBuilder initListMapFields = new StringBuilder();
        for (ProtobufField protobufField : protobufFields) {
            boolean isList = protobufField.isList();
            boolean isMap = protobufField.isMap();
            String express = "";
            if (isList) {
                if (FieldInfo.isListType(protobufField.getJavaField())) {
                    express = "new ArrayList()";
                } else if (FieldInfo.isSetType(protobufField.getJavaField())) {
                    express = "new HashSet()";
                }
            } else if (isMap) {
                express = "new HashMap()";
            }
            if (isList || isMap) {
                initListMapFields.append(FieldUtil.getInitListMapFieldDynamicString(protobufField, express));
            }

            if (protobufField.getProtobufFieldType() == ProtobufFieldTypeEnum.ENUM) {
                String clsName = protobufField.getJavaField().getType().getCanonicalName();
                if (!isList) {
                    express = "FieldUtil.getEnumValue(" + clsName + ".class, " + clsName + ".values()[0].name())";
                    // add set get method
                    String setToField = FieldUtil.getSetFieldDynamicString(protobufField,clazz,express);
                    miniTemplator.setVariable("enumInitialize", setToField);
                    miniTemplator.addBlock("enumFields");
                }
            }
        }
        miniTemplator.setVariable("initListMapFields", initListMapFields.toString());

        //todo 继续看下

        //处理字段赋值
        StringBuilder code = new StringBuilder();
        // 处理field解析
        for (ProtobufField protobufField : protobufFields) {
            boolean isList = protobufField.isList();
            String t = protobufField.getProtobufFieldType().getType();
            t = FieldUtil.capitalize(t);

            boolean listTypeCheck = false;
            String express;
            String objectDecodeExpress = "";
            String objectDecodeExpressSuffix = "";

            String decodeOrder = "-1";
            if (protobufField.getProtobufFieldType() != ProtobufFieldTypeEnum.DEFAULT) {
                decodeOrder = FieldUtil.makeTag(protobufField.getOrder(),
                        protobufField.getProtobufFieldType().getInternalFieldType().getWireType()) + "";
            } else {
                decodeOrder = "FieldUtil.makeTag(" + protobufField.getOrder() + ",WireFormat."
                        + protobufField.getProtobufFieldType().getWireFormat() + ")";
            }
            miniTemplator.setVariable("decodeOrder", decodeOrder);

            // enumeration type
            if (protobufField.getProtobufFieldType() == ProtobufFieldTypeEnum.ENUM) {
                String clsName = ClassHelper.getInternalName(protobufField.getJavaField().getType().getCanonicalName());
                if (isList) {
                    if (protobufField.getGenericKeyType() != null) {
                        Class cls = protobufField.getGenericKeyType();
                        clsName = ClassHelper.getInternalName(cls.getCanonicalName());
                    }
                }
                express = "FieldUtil.getEnumValue(" + clsName + ".class, FieldUtil.getEnumName(" + clsName
                        + ".values()," + "input.read" + t + "()))";
            } else {
                // here is the trick way to process BigDecimal and BigInteger
                if (protobufField.getProtobufFieldType() == ProtobufFieldTypeEnum.BIGDECIMAL || protobufField.getProtobufFieldType() == ProtobufFieldTypeEnum.BIGINTEGER) {
                    express = "new " + protobufField.getProtobufFieldType().getJavaType() +  "(input.read" + t + "())";
                } else {
                    express = "input.read" + t + "()";
                }

            }

            // if List type and element is object message type
            if (isList && protobufField.getProtobufFieldType() == ProtobufFieldTypeEnum.OBJECT) {
                if (protobufField.getGenericKeyType() != null) {
                    Class cls = protobufField.getGenericKeyType();

                    checkObjectType(protobufField, cls);

                    code.append("codec = ProtobufProxy.create(").append(cls.getCanonicalName()).append(".class");
                    String spath = "ProtobufProxy.OUTPUT_PATH.get()";
                    code.append(",").append(spath);
                    code.append(")").append(ClassCode.JAVA_LINE_BREAK);
                    objectDecodeExpress = code.toString();
                    code.setLength(0);

                    objectDecodeExpress += "int length = input.readRawVarint32()" + ClassCode.JAVA_LINE_BREAK;
                    objectDecodeExpress += "final int oldLimit = input.pushLimit(length)" + ClassCode.JAVA_LINE_BREAK;
                    listTypeCheck = true;
                    express = "(" + cls.getCanonicalName() + ") codec.readFrom(input)";

                }
            } else if (protobufField.isMap()) {

                String getMapCommand = getMapCommand(protobufField);

                if (protobufField.isEnumKeyType()) {
                    String enumClassName = protobufField.getGenericKeyType().getCanonicalName();
                    code.append("EnumHandler<").append(enumClassName).append("> keyhandler");
                    code.append("= new EnumHandler");
                    code.append("<").append(enumClassName).append(">() {");
                    code.append(ClassCode.LINE_BREAK);
                    code.append("public ").append(enumClassName).append(" handle(int value) {");
                    code.append(ClassCode.LINE_BREAK);
                    code.append("String enumName = FieldUtil.getEnumName(").append(enumClassName)
                            .append(".values(), value)");
                    code.append(ClassCode.JAVA_LINE_BREAK);
                    code.append("return ").append(enumClassName).append(".valueOf(enumName)");
                    code.append(ClassCode.JAVA_LINE_BREAK);
                    code.append("}}");
                    code.append(ClassCode.JAVA_LINE_BREAK);
                }

                if (protobufField.isEnumValueType()) {
                    String enumClassName = protobufField.getGenericValueType().getCanonicalName();
                    code.append("EnumHandler<").append(enumClassName).append("> handler");
                    code.append("= new EnumHandler");
                    code.append("<").append(enumClassName).append(">() {");
                    code.append(ClassCode.LINE_BREAK);
                    code.append("public ").append(enumClassName).append(" handle(int value) {");
                    code.append(ClassCode.LINE_BREAK);
                    code.append("String enumName = FieldUtil.getEnumName(").append(enumClassName)
                            .append(".values(), value)");
                    code.append(ClassCode.JAVA_LINE_BREAK);
                    code.append("return ").append(enumClassName).append(".valueOf(enumName)");
                    code.append(ClassCode.JAVA_LINE_BREAK);
                    code.append("}}");
                    code.append(ClassCode.JAVA_LINE_BREAK);
                }

                objectDecodeExpress = code.toString();
                code.setLength(0);

                express = "CodedConstant.putMapValue(input, " + getMapCommand + ",";
                express += FieldUtil.getMapFieldGenericParameterString(protobufField);
                if (protobufField.isEnumKeyType()) {
                    express += ", keyhandler";
                } else {
                    express += ", null";
                }
                if (protobufField.isEnumValueType()) {
                    express += ", handler";
                } else {
                    express += ", null";
                }
                express += ")";

            } else if (protobufField.getProtobufFieldType() == ProtobufFieldTypeEnum.OBJECT) { // if object
                // message
                // type
                Class cls = protobufField.getJavaField().getType();
                checkObjectType(protobufField, cls);
                String name = ClassHelper.getInternalName(cls.getCanonicalName()); // need
                // to
                // parse
                // nested
                // class
                code.append("codec = ProtobufProxy.create(").append(name).append(".class");

                String spath = "ProtobufProxy.OUTPUT_PATH.get()";
                code.append(",").append(spath);
                code.append(")").append(ClassCode.JAVA_LINE_BREAK);
                objectDecodeExpress = code.toString();
                code.setLength(0);

                objectDecodeExpress += "int length = input.readRawVarint32()" + ClassCode.JAVA_LINE_BREAK;
                objectDecodeExpress += "final int oldLimit = input.pushLimit(length)" + ClassCode.JAVA_LINE_BREAK;

                listTypeCheck = true;
                express = "(" + name + ") codec.readFrom(input)";
            }

            if (protobufField.getProtobufFieldType() == ProtobufFieldTypeEnum.BYTES) {
                express += ".toByteArray()";
            }

            String decodeFieldSetValue = FieldUtil.getSetFieldDynamicString(protobufField,clazz,express) + FieldUtil.JAVA_LINE_BREAK;

            if (listTypeCheck) {
                objectDecodeExpressSuffix += "input.checkLastTagWas(0)" + ClassCode.JAVA_LINE_BREAK;
                objectDecodeExpressSuffix += "input.popLimit(oldLimit)" + ClassCode.JAVA_LINE_BREAK;
            }

            String objectPackedDecodeExpress = "";
            // read packed type
            if (isList) {
                ProtobufFieldTypeEnum protobufFieldType = protobufField.getProtobufFieldType();
                if (protobufFieldType.isPrimitive() || protobufFieldType.isEnum()) {
                    code.append("if (tag == ")
                            .append(FieldUtil.makeTag(protobufField.getOrder(), WireFormat.WIRETYPE_LENGTH_DELIMITED));
                    code.append(") {").append(ClassCode.LINE_BREAK);

                    code.append("int length = input.readRawVarint32()").append(ClassCode.JAVA_LINE_BREAK);
                    code.append("int limit = input.pushLimit(length)").append(ClassCode.JAVA_LINE_BREAK);

                    code.append(FieldUtil.getSetFieldDynamicString(protobufField,clazz,express));

                    code.append("input.popLimit(limit)").append(ClassCode.JAVA_LINE_BREAK);

                    code.append("continue").append(ClassCode.JAVA_LINE_BREAK);
                    code.append("}").append(ClassCode.LINE_BREAK);

                    objectPackedDecodeExpress = code.toString();
                }
            }
            miniTemplator.setVariable("objectPackedDecodeExpress", objectPackedDecodeExpress);
            miniTemplator.setVariable("objectDecodeExpress", objectDecodeExpress);
            miniTemplator.setVariable("objectDecodeExpressSuffix", objectDecodeExpressSuffix);
            miniTemplator.setVariable("decodeFieldSetValue", decodeFieldSetValue);
            miniTemplator.addBlock("decodeFields");
        }

    }

    private static void checkObjectType(ProtobufField protobufField, Class cls) {
        if (FieldInfo.isPrimitiveType(cls)) {
            throw new RuntimeException("invalid generic type for List as Object type, current type is '" + cls.getName()
                    + "'  on field name '" + protobufField.getJavaField().getDeclaringClass().getName() + "#"
                    + protobufField.getJavaField().getName());
        }
    }

    private static String getMapCommand(ProtobufField protobufField) {
        String keyGeneric;
        keyGeneric = protobufField.getGenericKeyType().getCanonicalName();

        String valueGeneric;
        valueGeneric = protobufField.getGenericValueType().getCanonicalName();
        String getMapCommand = "(Map<" + keyGeneric;
        getMapCommand = getMapCommand + ", " + valueGeneric + ">)";
        getMapCommand = getMapCommand + FieldUtil.getGetterDynamicString(protobufField, clazz);
        return getMapCommand;
    }


    private static void loadProtobufField() {
        protobufFields = FieldUtil.getProtobufFieldList(clazz,
                clazz.getAnnotation(ProtobufEnableZigZap.class) != null
        );
    }

//    public static void main(String[] args) {
//        List<ProtobufField> protobufFieldList = FieldUtil.getProtobufFieldList(ArthasSampleRequest.class, false);
//        for (ProtobufField protobufField : protobufFieldList) {
//            String target = FieldUtil.getGetterDynamicString("target", protobufField.getJavaField(), ArthasSampleRequest.class, protobufField.isWildcardType());
//            System.out.println(target);
//        }
//    }

}