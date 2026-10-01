package com.example.a3dmodelviewer

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import com.example.a3dmodelviewer.render.ModelSceneHost
import com.example.a3dmodelviewer.ui.OverlayView

class MainActivity : Activity() {

    private lateinit var host: ModelSceneHost
    private lateinit var addButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val surfaceView = SurfaceView(this)
        host = ModelSceneHost(this, surfaceView)
        val overlay = OverlayView(this, host)
        host.overlay = overlay

        addButton = Button(this).apply {
            text = "Add model"
            setOnClickListener { showModelPicker() }
        }

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val root = FrameLayout(this).apply {
            addView(surfaceView, FrameLayout.LayoutParams(match, match))
            addView(overlay, FrameLayout.LayoutParams(match, match))
            addView(
                addButton,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = dp(16) }
            )
        }
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION") val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION") val bottom = insets.systemWindowInsetBottom
            host.topInset = top.toFloat()
            (addButton.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom + dp(16)
            addButton.requestLayout()
            insets
        }
    }

    private fun showModelPicker() {
        val files = assets.list("models")
            ?.filter { it.endsWith(".glb", ignoreCase = true) }
            ?.sorted()
            .orEmpty()
        if (files.isEmpty()) {
            Toast.makeText(this, "No .glb files found in assets/models", Toast.LENGTH_LONG).show()
            return
        }
        val names = files.map { prettyName(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add model")
            .setItems(names) { _, which -> host.addModel("models/${files[which]}", names[which]) }
            .show()
    }

    private fun prettyName(file: String) =
        file.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        host.resume()
    }

    override fun onPause() {
        host.pause()
        super.onPause()
    }

    override fun onDestroy() {
        host.destroy()
        super.onDestroy()
    }
}