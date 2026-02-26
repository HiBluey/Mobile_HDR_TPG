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
            val doc = builder.parse(InputSource(StringReader(xml)))
            val rectNodes = doc.getElementsByTagName("rectangle")

            for (i in 0 until rectNodes.length) {
                val node = rectNodes.item(i) as Element
                var rFloat = 0f; var gFloat = 0f; var bFloat = 0f

                val colexList = node.getElementsByTagName("colex")
                if (colexList.length > 0) {
                    val colex = colexList.item(0) as Element
                    val bits = colex.getAttribute("bits").toInt()
                    val maxValue = ((1 shl bits) - 1).toFloat()
                    rFloat = colex.getAttribute("red").toFloat() / maxValue
                    gFloat = colex.getAttribute("green").toFloat() / maxValue
                    bFloat = colex.getAttribute("blue").toFloat() / maxValue
                } else {
                    val colorList = node.getElementsByTagName("color")
                    if (colorList.length > 0) {
                        val color = colorList.item(0) as Element
                        rFloat = color.getAttribute("red").toFloat() / 255f
                        gFloat = color.getAttribute("green").toFloat() / 255f
                        bFloat = color.getAttribute("blue").toFloat() / 255f
                    }
                }

                var x = 0f; var y = 0f; var cx = 1f; var cy = 1f
                val geomList = node.getElementsByTagName("geometry")
                if (geomList.length > 0) {
                    val geom = geomList.item(0) as Element
                    x = geom.getAttribute("x").toFloat()
                    y = geom.getAttribute("y").toFloat()
                    cx = geom.getAttribute("cx").toFloat()
                    cy = geom.getAttribute("cy").toFloat()
                }

                val colorLong = Color.pack(
                    rFloat, gFloat, bFloat, 1f,
                    ColorSpace.get(ColorSpace.Named.BT2020_PQ)
                )

                parsedRects.add(PatchRect(colorLong, x, y, cx, cy))
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
