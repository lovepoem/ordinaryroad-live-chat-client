/*
 * MIT License
 *
 * Copyright (c) 2023 OrdinaryRoad
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package tech.ordinaryroad.live.chat.client.douyu.util;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import tech.ordinaryroad.live.chat.client.commons.base.msg.BaseMsg;
import tech.ordinaryroad.live.chat.client.douyu.constant.DouyuCmdEnum;
import tech.ordinaryroad.live.chat.client.douyu.msg.*;
import tech.ordinaryroad.live.chat.client.douyu.msg.base.BaseDouyuCmdMsg;
import tech.ordinaryroad.live.chat.client.douyu.msg.base.IDouyuMsg;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 参考：https://open.douyu.com/source/api/63
 *
 * @author mjz
 * @date 2023/1/6
 */
@Slf4j
public class DouyuCodecUtil {

    public static final String[] IGNORE_PROPERTIES = {"OBJECT_MAPPER", "unknownProperties"};

    public static final short MSG_TYPE_SEND = 689;
    public static final short MSG_TYPE_RECEIVE = 690;

    public static int sequence = 0;

    public static final short FRAME_HEADER_LENGTH = 8;

    public static ByteBuf encode(BaseDouyuCmdMsg msg) {
        ByteBuf out = Unpooled.buffer(FRAME_HEADER_LENGTH);
        String bodyDouyuSttString = StrUtil.nullToEmpty(toDouyuSttString(msg));
        byte[] bodyBytes = bodyDouyuSttString.getBytes(StandardCharsets.UTF_8);
        // TODO length
        int length = bodyBytes.length + FRAME_HEADER_LENGTH;
        out.writeIntLE(length);
        out.writeIntLE(length);
        out.writeShortLE(MSG_TYPE_SEND);
        out.writeByte(0);
        out.writeByte(0);
        out.writeBytes(bodyBytes);
        return out;
    }

    public static List<IDouyuMsg> decode(ByteBuf in) {
        List<IDouyuMsg> msgList = new ArrayList<>();
        Queue<ByteBuf> pendingByteBuf = new LinkedList<>();

        do {
            Optional<IDouyuMsg> msg = doDecode(in, pendingByteBuf);
            msg.ifPresent(msgList::add);
            in = pendingByteBuf.poll();
        } while (in != null);

        return msgList;
    }

    /**
     * 执行解码操作
     *
     * @param in             handler收到的一条消息
     * @param pendingByteBuf 用于存放未读取完的ByteBuf
     * @return Optional<IDouyuMsg> 何时为空值：不支持的{@link DouyuCmdEnum}，{@link #parseDouyuSttString(String, short)}反序列化失败
     */
    private static Optional<IDouyuMsg> doDecode(ByteBuf in, Queue<ByteBuf> pendingByteBuf) {
        int length = in.readIntLE();
        in.readIntLE();
        // MSG_TYPE_RECEIVE
        short msgType = in.readShortLE();
        if (msgType != MSG_TYPE_RECEIVE) {
            log.error("decode消息类型 非 收到的消息");
        }
        in.readByte();
        in.readByte();
        int contentLength = length - FRAME_HEADER_LENGTH;
        byte[] inputBytes = new byte[contentLength];
        in.readBytes(inputBytes);
        if (in.readableBytes() != 0) {
            log.error("in.readableBytes() {}", in.readableBytes());
            pendingByteBuf.offer(in);
        }

        String bodyDouyuSttString = new String(inputBytes);
        return Optional.ofNullable(parseDouyuSttString(bodyDouyuSttString, msgType));
    }

    public static final String SPLITTER = "@=";
    public static final String END = "/";
    public static final String SUFFIX = "\0";

    /**
     * <pre>{@code @S/ -> @AS@S}</pre>
     *
     * @param string
     * @return
     */
    public static String escape(String string) {
        return string == null ? StrUtil.EMPTY : (string.replaceAll("/", "@S").replaceAll("@", "@A"));
    }

    /**
     * <pre>{@code @AS@S -> @S/}</pre>
     *
     * @param string
     * @return
     */
    public static String unescape(String string) {
        return string == null ? StrUtil.EMPTY : (string.replaceAll("@A", "@").replaceAll("@S", "/"));
    }

    public static String toDouyuSttString(Object object) {
        StringBuffer sb = new StringBuffer();
        Class<?> objectClass = object.getClass();
        Field[] fields = ReflectUtil.getFields(objectClass, field -> !ArrayUtil.contains(IGNORE_PROPERTIES, field.getName()));
        for (Field field : fields) {
            String key = field.getName();
            Method method = ReflectUtil.getMethod(objectClass, true, "get" + key);
            Object value = ReflectUtil.invoke(object, method);
//            Object value = ReflectUtil.getFieldValue(object, field);
            sb.append(escape(key))
                    .append(SPLITTER);
            if (value instanceof Iterable<?> iterable) {
                StringBuffer iterableStringBuffer = new StringBuffer();
                for (Object o : iterable) {
                    iterableStringBuffer.append(escape(StrUtil.toStringOrNull(o)))
                            .append(END);
                }
                sb.append(escape(iterableStringBuffer.toString()));
            } else if (value instanceof Map<?, ?> map) {
                StringBuffer mapStringBuffer = new StringBuffer();
                map.forEach((mapKey, mapValue) -> {
                    mapStringBuffer.append(escape(StrUtil.toStringOrNull(mapKey)))
                            .append(SPLITTER)
                            .append(escape(StrUtil.toStringOrNull(mapValue)))
                            .append(END);
                });
                sb.append(escape(mapStringBuffer.toString()));
            } else {
                sb.append(escape(StrUtil.toStringOrNull(value)))
                        .append(END);
            }
        }
        sb.append(SUFFIX);
        return sb.toString();
    }

    public static IDouyuMsg parseDouyuSttString(String string, short msgType) {
        Map<String, Object> stringObjectMap = parseDouyuSttStringToMap(string);
        String type = (String) stringObjectMap.get("type");
        DouyuCmdEnum cmdEnum = DouyuCmdEnum.getByString(type);

        if (cmdEnum == null) {
            // TODO 不支持
            log.warn("暂不支持 type {}", type);
            return null;
        }

        Class<IDouyuMsg> msgClass = getDouyuMsgClassByType(cmdEnum, msgType);
        if (msgClass == null) {
            // TODO 不支持
            log.warn("暂不支持 cmdEnum {}, msgType {}", cmdEnum, msgType);
            return null;
        }

        IDouyuMsg t = ReflectUtil.newInstance(msgClass);
        stringObjectMap.forEach((key, value) -> {
            Field field = ReflectUtil.getField(t.getClass(), key);
            // 未知key
            if (field == null) {
                if (value instanceof Iterable<?> iterable) {
                    ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
                    iterable.forEach(o -> arrayNode.add((String) o));
                    ((BaseDouyuCmdMsg) t).getUnknownProperties().put(key, arrayNode);
                } else if (value instanceof Map<?, ?> map) {
                    ObjectNode objectNode = JsonNodeFactory.instance.objectNode();
                    map.forEach((o, o2) -> {
                        objectNode.put((String) o, (String) o2);
                    });
                    ((BaseDouyuCmdMsg) t).getUnknownProperties().put(key, objectNode);
                } else {
                    TextNode textNode = JsonNodeFactory.instance.textNode((String) value);
                    ((BaseDouyuCmdMsg) t).getUnknownProperties().put(key, textNode);
                }
                log.debug("未知key {} {}，已存放于unknownProperties中", msgClass, key);
            } else {
                ReflectUtil.setFieldValue(t, field, value);
            }
        });
        return t;
    }

    public static Map<String, Object> parseDouyuSttStringToMap(String string) {
        Map<String, Object> stringObjectMap = new HashMap<>();
        for (String s : string.split(END)) {
            String[] entry = s.split(SPLITTER);
            String key = unescape(entry[0]);
            String value = unescape(ArrayUtil.get(entry, 1));
            Object valueObject = null;
            if (value != null && value.endsWith(END)) {
                for (String valueSplit : value.split(END)) {
                    String valueSplitUnescape = unescape(valueSplit);
                    if (StrUtil.isBlank(valueSplitUnescape)) {
                        continue;
                    }
                    // Map
                    if (valueSplitUnescape.contains(END)) {
                        log.info("Map {}", valueSplitUnescape);
                        if (valueObject == null) {
                            valueObject = new HashMap<String, String>();
                        }
                        String[] valueSplitUnescapeSplit = unescape(valueSplitUnescape).split(END);
                        for (String s1 : valueSplitUnescapeSplit) {
                            if (StrUtil.isBlank(s1)) {
                                continue;
                            }
                            String[] split = s1.split(SPLITTER);
                            ((Map<String, String>) valueObject).put(unescape(split[0]), unescape(split[1]));
                        }
                    }
                    // List
                    else {
                        log.info("List {}", valueSplitUnescape);
                        if (valueObject == null) {
                            valueObject = new ArrayList<String>();
                        }
                        ((List<String>) valueObject).add(unescape(valueSplitUnescape));
                    }
                }
            } else {
                valueObject = value;
            }
            stringObjectMap.put(key, valueObject);
        }
        return stringObjectMap;
    }

    public static <T extends IDouyuMsg> Class<T> getDouyuMsgClassByType(DouyuCmdEnum douyuCmdEnum, short msgType) {
        if (douyuCmdEnum == null) {
            return null;
        }
        Class<?> msgClass;
        switch (douyuCmdEnum) {
            case loginreq -> {
                msgClass = LoginreqMsg.class;
            }
            case loginres -> {
                msgClass = LoginresMsg.class;
            }
            case mrkl -> {
                if (msgType == MSG_TYPE_RECEIVE) {
                    msgClass = HeartbeatReplyMsg.class;
                } else if (msgType == MSG_TYPE_SEND) {
                    msgClass = HeartbeatMsg.class;
                } else {
                    msgClass = null;
                }
            }
            default -> {
                msgClass = DouyuCmdMsg.class;
            }
        }
        return (Class<T>) msgClass;
    }
}
