package com.liyi.mobilehdrtpg

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.w3c.dom.Element
import java.io.DataInputStream
import java.io.StringReader
import java.net.Socket
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var hdrPatchView: HdrPatchView
    private lateinit var controlPanel: LinearLayout
    private var isConnected = false
    private var clientSocket: Socket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 申请 10-bit 缓冲区和 HDR 模式
        window.setFormat(PixelFormat.RGBA_1010102)
        window.colorMode = ActivityInfo.COLOR_MODE_HDR
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        val inputIp = findViewById<EditText>(R.id.inputIp)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        tvStatus = findViewById(R.id.tvStatus)
        hdrPatchView = findViewById(R.id.hdrPatchView)
        controlPanel = findViewById(R.id.controlPanel)

        // 点击按钮连接或断开
        btnConnect.setOnClickListener {
            val ip = inputIp.text.toString().trim()
            if (ip.isNotEmpty() && !isConnected) {
                connectToColourSpace(ip)
            } else if (isConnected) {
                disconnect()
            }
        }

        // 点击全屏色块区域，可以手动显示/隐藏顶部的控制面板
        hdrPatchView.setOnClickListener {
            if (controlPanel.visibility == View.VISIBLE) {
                controlPanel.visibility = View.GONE
            } else {
                controlPanel.visibility = View.VISIBLE
            }
        }
    }

    private fun connectToColourSpace(ip: String) {
        tvStatus.text = "Status: Connecting... | v1.1"
        Thread {
            try {
                clientSocket = Socket(ip, 20002)
                isConnected = true
                runOnUiThread {
                    tvStatus.text = "Status: Connected to $ip | v1.1"
                    findViewById<Button>(R.id.btnConnect).text = "Disconnect"
                    // 连接成功，自动隐藏控制面板，进入纯净全屏模式！
                    controlPanel.visibility = View.GONE
                }

                val inputStream = DataInputStream(clientSocket!!.getInputStream())

                while (isConnected) {
                    val messageLength = inputStream.readInt()
                    if (messageLength <= 0) break

                    val dataBytes = ByteArray(messageLength)
                    inputStream.readFully(dataBytes)
                    val xmlString = String(dataBytes, Charsets.UTF_8)

                    val rects = parseXml(xmlString)

                    runOnUiThread {
                        hdrPatchView.updateRectangles(rects)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                disconnect()
            }
        }.start()
    }

    private fun parseXml(xml: String): List<PatchRect> {
        val parsedRects = mutableListOf<PatchRect>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            // 解析 XML 字符串
            val doc = builder.parse(InputSource(StringReader(xml)))

            // ==========================================
            // 核心修复：通用的智能颜色提取器
            // 解决 Calman / ColourSpace 位深标记混乱与溢出变黑问题
            // ==========================================
            fun extractColor(element: Element): FloatArray {
                val r = element.getAttribute("red").toFloatOrNull() ?: 0f
                val g = element.getAttribute("green").toFloatOrNull() ?: 0f
                val b = element.getAttribute("blue").toFloatOrNull() ?: 0f

                var bits = 8
                if (element.hasAttribute("bits")) {
                    bits = element.getAttribute("bits").toIntOrNull() ?: 8
                } else {
                    // 智能推断：如果软件没发 bits，但数值超过了 255，自动升级位深
                    if (r > 255f || g > 255f || b > 255f) {
                        bits = if (r > 1023f || g > 1023f || b > 1023f) 16 else 10
                    }
                }

                val maxValue = ((1 shl bits) - 1).toFloat()

                // coerceIn 极其重要：强制将结果卡在 0.0f - 1.0f 之间，彻底杜绝底层溢出变黑
                return floatArrayOf(
                    (r / maxValue).coerceIn(0f, 1f),
                    (g / maxValue).coerceIn(0f, 1f),
                    (b / maxValue).coerceIn(0f, 1f)
                )
            }

            val rectNodes = doc.getElementsByTagName("rectangle")

            if (rectNodes.length > 0) {
                // ----------------------------------------
                // 模式 A：ColourSpace 协议
                // ----------------------------------------
                for (i in 0 until rectNodes.length) {
                    val node = rectNodes.item(i) as Element
                    var rgb = floatArrayOf(0f, 0f, 0f)

                    val colexList = node.getElementsByTagName("colex")
                    if (colexList.length > 0) {
                        rgb = extractColor(colexList.item(0) as Element)
                    } else {
                        val colorList = node.getElementsByTagName("color")
                        if (colorList.length > 0) {
                            rgb = extractColor(colorList.item(0) as Element)
                        }
                    }

                    var x = 0f; var y = 0f; var cx = 1f; var cy = 1f
                    val geomList = node.getElementsByTagName("geometry")
                    if (geomList.length > 0) {
                        val geom = geomList.item(0) as Element
                        x = geom.getAttribute("x").toFloatOrNull() ?: 0f
                        y = geom.getAttribute("y").toFloatOrNull() ?: 0f
                        cx = geom.getAttribute("cx").toFloatOrNull() ?: 1f
                        cy = geom.getAttribute("cy").toFloatOrNull() ?: 1f
                    }

                    val colorLong = Color.pack(rgb[0], rgb[1], rgb[2], 1f, ColorSpace.get(ColorSpace.Named.BT2020_PQ))
                    parsedRects.add(PatchRect(colorLong, x, y, cx, cy))
                }
            } else {
                // ----------------------------------------
                // 模式 B：Calman (DaVinci Resolve) 协议
                // ----------------------------------------

                // 先画背景 <background>
                val bgNodes = doc.getElementsByTagName("background")
                if (bgNodes.length > 0) {
                    val bg = bgNodes.item(0) as Element
                    val bgRgb = extractColor(bg)
                    val bgColorLong = Color.pack(bgRgb[0], bgRgb[1], bgRgb[2], 1f, ColorSpace.get(ColorSpace.Named.BT2020_PQ))
                    parsedRects.add(PatchRect(bgColorLong, 0f, 0f, 1f, 1f))
                }

                // 再画前景测量色块 <color> 或 <colex>
                var fgRgb = floatArrayOf(0f, 0f, 0f)
                val colexList = doc.getElementsByTagName("colex")
                if (colexList.length > 0) {
                    fgRgb = extractColor(colexList.item(0) as Element)
                } else {
                    val colorList = doc.getElementsByTagName("color")
                    if (colorList.length > 0) {
                        fgRgb = extractColor(colorList.item(0) as Element)
                    }
                }

                var x = 0f; var y = 0f; var cx = 1f; var cy = 1f
                val geomList = doc.getElementsByTagName("geometry")
                if (geomList.length > 0) {
                    val geom = geomList.item(0) as Element
                    x = geom.getAttribute("x").toFloatOrNull() ?: 0f
                    y = geom.getAttribute("y").toFloatOrNull() ?: 0f
                    cx = geom.getAttribute("cx").toFloatOrNull() ?: 1f
                    cy = geom.getAttribute("cy").toFloatOrNull() ?: 1f
                }

                val fgColorLong = Color.pack(fgRgb[0], fgRgb[1], fgRgb[2], 1f, ColorSpace.get(ColorSpace.Named.BT2020_PQ))
                parsedRects.add(PatchRect(fgColorLong, x, y, cx, cy))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return parsedRects
    }

    private fun disconnect() {
        isConnected = false
        try {
            clientSocket?.close()
        } catch (e: Exception) {}

        runOnUiThread {
            tvStatus.text = "Status: Disconnected | v1.1"
            findViewById<Button>(R.id.btnConnect).text = "Connect"
            // 断开连接后，自动恢复控制面板显示
            controlPanel.visibility = View.VISIBLE
            hdrPatchView.updateRectangles(emptyList())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
}
