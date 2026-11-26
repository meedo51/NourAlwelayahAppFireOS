package org.json

import java.lang.reflect.Array as ReflectArray

open class JSONException(message: String?, cause: Throwable? = null) : RuntimeException(message, cause)

class JSONObject() {
    private val map = LinkedHashMap<String, Any?>()

    constructor(copyFrom: Map<*, *>?) : this() {
        copyFrom?.forEach { (k, v) ->
            if (k != null) {
                map[k.toString()] = wrapValue(v)
            }
        }
    }

    constructor(json: String?) : this() {
        val parsed = MiniJsonParser(json.orEmpty()).parseObject()
        map.putAll(parsed.map)
    }

    fun length(): Int = map.size

    fun keys(): MutableIterator<String> = map.keys.iterator()

    fun has(name: String?): Boolean = name != null && map.containsKey(name) && map[name] !== NULL

    fun isNull(name: String?): Boolean = name == null || !map.containsKey(name) || map[name] == null || map[name] === NULL

    fun put(name: String, value: Boolean): JSONObject {
        map[name] = value
        return this
    }

    fun put(name: String, value: Double): JSONObject {
        map[name] = value
        return this
    }

    fun put(name: String, value: Int): JSONObject {
        map[name] = value
        return this
    }

    fun put(name: String, value: Long): JSONObject {
        map[name] = value
        return this
    }

    fun put(name: String, value: Any?): JSONObject {
        if (value == null) {
            map.remove(name)
        } else {
            map[name] = wrapValue(value)
        }
        return this
    }

    fun opt(name: String?): Any? = if (name == null) null else map[name]

    fun get(name: String): Any {
        if (!map.containsKey(name)) throw JSONException("No value for $name")
        return map[name] ?: NULL
    }

    fun getBoolean(name: String): Boolean {
        val v = get(name)
        return toBoolean(v) ?: throw JSONException("Value $v at $name is not Boolean")
    }

    @JvmOverloads
    fun optBoolean(name: String?, fallback: Boolean = false): Boolean {
        val v = opt(name) ?: return fallback
        return toBoolean(v) ?: fallback
    }

    fun getDouble(name: String): Double {
        val v = get(name)
        return toDouble(v) ?: throw JSONException("Value $v at $name is not Double")
    }

    @JvmOverloads
    fun optDouble(name: String?, fallback: Double = Double.NaN): Double {
        val v = opt(name) ?: return fallback
        return toDouble(v) ?: fallback
    }

    fun getInt(name: String): Int {
        val v = get(name)
        return toDouble(v)?.toInt() ?: throw JSONException("Value $v at $name is not Int")
    }

    @JvmOverloads
    fun optInt(name: String?, fallback: Int = 0): Int {
        val v = opt(name) ?: return fallback
        return toDouble(v)?.toInt() ?: fallback
    }

    fun getLong(name: String): Long {
        val v = get(name)
        return toLong(v) ?: throw JSONException("Value $v at $name is not Long")
    }

    @JvmOverloads
    fun optLong(name: String?, fallback: Long = 0L): Long {
        val v = opt(name) ?: return fallback
        return toLong(v) ?: fallback
    }

    fun getString(name: String): String {
        val v = get(name)
        if (v === NULL) throw JSONException("Value at $name is null")
        return v.toString()
    }

    @JvmOverloads
    fun optString(name: String?, fallback: String = ""): String {
        val v = opt(name)
        return if (v == null || v === NULL) fallback else v.toString()
    }

    fun getJSONArray(name: String): JSONArray {
        val v = get(name)
        return v as? JSONArray ?: throw JSONException("Value $v at $name is not JSONArray")
    }

    fun optJSONArray(name: String?): JSONArray? = opt(name) as? JSONArray

    fun getJSONObject(name: String): JSONObject {
        val v = get(name)
        return v as? JSONObject ?: throw JSONException("Value $v at $name is not JSONObject")
    }

    fun optJSONObject(name: String?): JSONObject? = opt(name) as? JSONObject

    override fun toString(): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            sb.append(quote(k)).append(':').append(valueToJsonString(v))
        }
        sb.append('}')
        return sb.toString()
    }

    companion object {
        @JvmField
        val NULL: Any = object {
            override fun equals(other: Any?): Boolean = other === this || other == null
            override fun hashCode(): Int = 0
            override fun toString(): String = "null"
        }

        @JvmStatic
        fun quote(data: String?): String {
            if (data == null) return "\"\""
            val sb = StringBuilder("\"")
            for (c in data) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c.code < 0x20) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
            sb.append('"')
            return sb.toString()
        }

        internal fun wrapValue(v: Any?): Any {
            return when (v) {
                null, NULL -> NULL
                is JSONObject, is JSONArray, is Boolean, is Number, is String -> v
                is Collection<*> -> JSONArray(v)
                is Map<*, *> -> JSONObject(v)
                else -> if (v.javaClass.isArray) JSONArray(v) else v.toString()
            }
        }

        internal fun valueToJsonString(v: Any?): String {
            return when (v) {
                null, NULL -> "null"
                is JSONObject -> v.toString()
                is JSONArray -> v.toString()
                is Boolean -> v.toString()
                is Number -> v.toString()
                else -> quote(v.toString())
            }
        }

        internal fun toBoolean(v: Any?): Boolean? = when (v) {
            is Boolean -> v
            is String -> when {
                v.equals("true", ignoreCase = true) -> true
                v.equals("false", ignoreCase = true) -> false
                else -> null
            }
            else -> null
        }

        internal fun toDouble(v: Any?): Double? = when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }

        internal fun toLong(v: Any?): Long? = when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong()
            else -> null
        }
    }
}

class JSONArray() {
    private val list = ArrayList<Any?>()

    constructor(copyFrom: Collection<*>?) : this() {
        copyFrom?.forEach { list.add(JSONObject.wrapValue(it)) }
    }

    constructor(json: String?) : this() {
        val parsed = MiniJsonParser(json.orEmpty()).parseArray()
        list.addAll(parsed.list)
    }

    constructor(array: Any?) : this() {
        if (array != null && array.javaClass.isArray) {
            val len = ReflectArray.getLength(array)
            for (i in 0 until len) {
                list.add(JSONObject.wrapValue(ReflectArray.get(array, i)))
            }
        } else if (array is Collection<*>) {
            array.forEach { list.add(JSONObject.wrapValue(it)) }
        }
    }

    fun length(): Int = list.size

    fun put(value: Boolean): JSONArray {
        list.add(value)
        return this
    }

    fun put(value: Double): JSONArray {
        list.add(value)
        return this
    }

    fun put(value: Int): JSONArray {
        list.add(value)
        return this
    }

    fun put(value: Long): JSONArray {
        list.add(value)
        return this
    }

    fun put(value: Any?): JSONArray {
        list.add(JSONObject.wrapValue(value))
        return this
    }

    fun opt(index: Int): Any? = if (index in 0 until list.size) list[index] else null

    fun get(index: Int): Any {
        if (index !in 0 until list.size) throw JSONException("Index $index out of range")
        return list[index] ?: JSONObject.NULL
    }

    fun getBoolean(index: Int): Boolean =
        JSONObject.toBoolean(get(index)) ?: throw JSONException("Not a boolean at $index")

    @JvmOverloads
    fun optBoolean(index: Int, fallback: Boolean = false): Boolean =
        JSONObject.toBoolean(opt(index)) ?: fallback

    fun getDouble(index: Int): Double =
        JSONObject.toDouble(get(index)) ?: throw JSONException("Not a double at $index")

    @JvmOverloads
    fun optDouble(index: Int, fallback: Double = Double.NaN): Double =
        JSONObject.toDouble(opt(index)) ?: fallback

    fun getInt(index: Int): Int =
        JSONObject.toDouble(get(index))?.toInt() ?: throw JSONException("Not an int at $index")

    @JvmOverloads
    fun optInt(index: Int, fallback: Int = 0): Int =
        JSONObject.toDouble(opt(index))?.toInt() ?: fallback

    fun getLong(index: Int): Long =
        JSONObject.toLong(get(index)) ?: throw JSONException("Not a long at $index")

    @JvmOverloads
    fun optLong(index: Int, fallback: Long = 0L): Long =
        JSONObject.toLong(opt(index)) ?: fallback

    fun getString(index: Int): String {
        val v = get(index)
        if (v === JSONObject.NULL) throw JSONException("Null at $index")
        return v.toString()
    }

    @JvmOverloads
    fun optString(index: Int, fallback: String = ""): String {
        val v = opt(index)
        return if (v == null || v === JSONObject.NULL) fallback else v.toString()
    }

    fun getJSONObject(index: Int): JSONObject =
        get(index) as? JSONObject ?: throw JSONException("Not a JSONObject at $index")

    fun optJSONObject(index: Int): JSONObject? = opt(index) as? JSONObject

    override fun toString(): String {
        val sb = StringBuilder("[")
        for (i in list.indices) {
            if (i > 0) sb.append(',')
            sb.append(JSONObject.valueToJsonString(list[i]))
        }
        sb.append(']')
        return sb.toString()
    }
}

private class MiniJsonParser(private val src: String) {
    private var pos = 0

    private fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    fun parseObject(): JSONObject {
        skipWhitespace()
        if (pos >= src.length || src[pos] != '{') throw JSONException("Expected '{' at $pos")
        pos++
        val obj = JSONObject()
        skipWhitespace()
        if (pos < src.length && src[pos] == '}') {
            pos++
            return obj
        }
        while (pos < src.length) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            if (pos >= src.length || src[pos] != ':') throw JSONException("Expected ':' at $pos")
            pos++
            skipWhitespace()
            val value = parseValue()
            obj.put(key, value)
            skipWhitespace()
            if (pos < src.length && src[pos] == ',') {
                pos++
                continue
            }
            if (pos < src.length && src[pos] == '}') {
                pos++
                return obj
            }
            throw JSONException("Expected ',' or '}' at $pos")
        }
        throw JSONException("Unterminated JSONObject")
    }

    fun parseArray(): JSONArray {
        skipWhitespace()
        if (pos >= src.length || src[pos] != '[') throw JSONException("Expected '[' at $pos")
        pos++
        val arr = JSONArray()
        skipWhitespace()
        if (pos < src.length && src[pos] == ']') {
            pos++
            return arr
        }
        while (pos < src.length) {
            skipWhitespace()
            arr.put(parseValue())
            skipWhitespace()
            if (pos < src.length && src[pos] == ',') {
                pos++
                continue
            }
            if (pos < src.length && src[pos] == ']') {
                pos++
                return arr
            }
            throw JSONException("Expected ',' or ']' at $pos")
        }
        throw JSONException("Unterminated JSONArray")
    }

    private fun parseValue(): Any {
        skipWhitespace()
        if (pos >= src.length) throw JSONException("Unexpected EOF")
        return when (src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            else -> parseNumber()
        }
    }

    private fun parseString(): String {
        if (pos >= src.length || src[pos] != '"') throw JSONException("Expected '\"' at $pos")
        pos++
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = src[pos++]
            if (c == '"') return sb.toString()
            if (c == '\\') {
                if (pos >= src.length) throw JSONException("Unterminated escape")
                when (val esc = src[pos++]) {
                    '"', '\\', '/' -> sb.append(esc)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 > src.length) throw JSONException("Invalid unicode escape")
                        val hex = src.substring(pos, pos + 4)
                        sb.append(hex.toInt(16).toChar())
                        pos += 4
                    }
                    else -> sb.append(esc)
                }
            } else {
                sb.append(c)
            }
        }
        throw JSONException("Unterminated string")
    }

    private fun parseBoolean(): Boolean {
        return when {
            src.startsWith("true", pos) -> {
                pos += 4
                true
            }
            src.startsWith("false", pos) -> {
                pos += 5
                false
            }
            else -> throw JSONException("Invalid boolean at $pos")
        }
    }

    private fun parseNull(): Any {
        if (src.startsWith("null", pos)) {
            pos += 4
            return JSONObject.NULL
        }
        throw JSONException("Invalid null at $pos")
    }

    private fun parseNumber(): Number {
        val start = pos
        while (pos < src.length && src[pos] in "0123456789+-.eE") {
            pos++
        }
        val raw = src.substring(start, pos)
        if ('.' in raw || 'e' in raw || 'E' in raw) {
            return raw.toDoubleOrNull() ?: throw JSONException("Invalid number '$raw'")
        }
        val l = raw.toLongOrNull() ?: throw JSONException("Invalid number '$raw'")
        return if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l
    }
}
