package com.example.a3dmodelviewer.render

import android.util.Log
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PartLabel(val text: String, val x: Float, val y: Float, val z: Float)

object GlbLabelParser {
    private const val TAG = "GlbLabelParser"
    private const val GLB_MAGIC = 0x46546C67   // "glTF"
    private const val CHUNK_JSON = 0x4E4F534A  // "JSON"

    fun parse(glb: ByteBuffer): List<PartLabel> = try {
        parseInternal(glb)
    } catch (t: Throwable) {
        Log.w(TAG, "Label parsing failed: $t")
        emptyList()
    }

    private fun parseInternal(glb: ByteBuffer): List<PartLabel> {
        val buf = glb.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (buf.capacity() < 20 || buf.getInt(0) != GLB_MAGIC) return emptyList()
        val jsonLength = buf.getInt(12)
        if (buf.getInt(16) != CHUNK_JSON || jsonLength <= 0 || 20 + jsonLength > buf.capacity()) {
            return emptyList()
        }
        val bytes = ByteArray(jsonLength)
        buf.position(20)
        buf.get(bytes)

        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val nodes = root.optJSONArray("nodes") ?: return emptyList()
        val n = nodes.length()

        val parent = IntArray(n) { -1 }
        for (i in 0 until n) {
            val children = nodes.getJSONObject(i).optJSONArray("children") ?: continue
            for (j in 0 until children.length()) {
                val c = children.getInt(j)
                if (c in 0 until n) parent[c] = i
            }
        }

        val world = arrayOfNulls<FloatArray>(n)
        fun worldOf(i: Int, depth: Int): FloatArray {
            world[i]?.let { return it }
            val local = localMatrix(nodes.getJSONObject(i))
            val p = parent[i]
            val m = if (p >= 0 && depth < 128) multiply(worldOf(p, depth + 1), local) else local
            world[i] = m
            return m
        }

        val out = ArrayList<PartLabel>()
        for (i in 0 until n) {
            val text = nodes.getJSONObject(i)
                .optJSONObject("extras")?.optString("prop", "")?.trim().orEmpty()
            if (text.isEmpty()) continue
            val m = worldOf(i, 0)
            out.add(PartLabel(text, m[12], m[13], m[14]))
        }
        return out
    }

    /** Column-major 4x4 local matrix from "matrix" or T*R*S. */
    private fun localMatrix(node: JSONObject): FloatArray {
        node.optJSONArray("matrix")?.let { a ->
            if (a.length() == 16) return FloatArray(16) { a.getDouble(it).toFloat() }
        }
        val t = node.optJSONArray("translation")
        val r = node.optJSONArray("rotation")
        val s = node.optJSONArray("scale")
        val tx = t?.optDouble(0, 0.0)?.toFloat() ?: 0f
        val ty = t?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val tz = t?.optDouble(2, 0.0)?.toFloat() ?: 0f
        val qx = r?.optDouble(0, 0.0)?.toFloat() ?: 0f
        val qy = r?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val qz = r?.optDouble(2, 0.0)?.toFloat() ?: 0f
        val qw = r?.optDouble(3, 1.0)?.toFloat() ?: 1f
        val sx = s?.optDouble(0, 1.0)?.toFloat() ?: 1f
        val sy = s?.optDouble(1, 1.0)?.toFloat() ?: 1f
        val sz = s?.optDouble(2, 1.0)?.toFloat() ?: 1f

        val xx = qx * qx; val yy = qy * qy; val zz = qz * qz
        val xy = qx * qy; val xz = qx * qz; val yz = qy * qz
        val wx = qw * qx; val wy = qw * qy; val wz = qw * qz

        val m = FloatArray(16)
        m[0] = (1 - 2 * (yy + zz)) * sx; m[1] = 2 * (xy + wz) * sx;       m[2] = 2 * (xz - wy) * sx;       m[3] = 0f
        m[4] = 2 * (xy - wz) * sy;       m[5] = (1 - 2 * (xx + zz)) * sy; m[6] = 2 * (yz + wx) * sy;       m[7] = 0f
        m[8] = 2 * (xz + wy) * sz;       m[9] = 2 * (yz - wx) * sz;       m[10] = (1 - 2 * (xx + yy)) * sz; m[11] = 0f
        m[12] = tx; m[13] = ty; m[14] = tz; m[15] = 1f
        return m
    }

    /** a * b, column-major. */
    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(16)
        for (c in 0 until 4) for (r in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += a[k * 4 + r] * b[c * 4 + k]
            o[c * 4 + r] = sum
        }
        return o
    }
}