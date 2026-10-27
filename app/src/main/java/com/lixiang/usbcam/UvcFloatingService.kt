package com.lixiang.usbcam

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.os.IBinder
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size

class UvcFloatingService : Service() {

    private lateinit var displayManager: DisplayManager
    private var currentDisplayIndex = 0
    private var currentWindowManager: WindowManager? = null
    private var rootLayout: FrameLayout? = null
    private var textureView: TextureView? = null
    private var previewSurface: Surface? = null
    private lateinit var windowParams: WindowManager.LayoutParams

    private var cameraHelper: ICameraHelper? = null

    private var isExpanded = false
    private var isMirrored = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays

        // Если пассажирский монитор активен (индекс 1), открываем на нем
        currentDisplayIndex = if (displays.size > 1) 1 else 0

        attachWindowToDisplay(displays[currentDisplayIndex])
        initUsbCamera()
    }

    private fun startForegroundNotification() {
        val channelId = "uvc_baby_cam"
        val channel = NotificationChannel(channelId, "USB Baby Camera", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Детская камера активна")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        startForeground(101, notification)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachWindowToDisplay(targetDisplay: Display) {
        previewSurface?.let {
            cameraHelper?.removeSurface(it)
            it.release()
            previewSurface = null
        }
        rootLayout?.let {
            try { currentWindowManager?.removeView(it) } catch (_: Exception) {}
        }

        val displayContext = createDisplayContext(targetDisplay)
        currentWindowManager = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val width = if (isExpanded) 1120 else 640
        val height = if (isExpanded) 680 else 400

        windowParams = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        rootLayout = FrameLayout(displayContext).apply {
            setBackgroundColor(Color.parseColor("#CC111111"))
        }

        textureView = TextureView(displayContext).apply {
            scaleX = if (isMirrored) -1f else 1f
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                    previewSurface = Surface(surface)
                    previewSurface?.let { cameraHelper?.addSurface(it, false) }
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    previewSurface?.let {
                        cameraHelper?.removeSurface(it)
                        it.release()
                        previewSurface = null
                    }
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }

        val controlBar = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#99000000"))
            gravity = Gravity.END
        }

        val btnSwitchScreen = createControlButton(displayContext, "⇄ Экран") {
            switchMonitor()
        }
        val btnMirror = createControlButton(displayContext, "Зерк.") {
            isMirrored = !isMirrored
            textureView?.scaleX = if (isMirrored) -1f else 1f
        }
        val btnResize = createControlButton(displayContext, "Размер") {
            isExpanded = !isExpanded
            windowParams.width = if (isExpanded) 1120 else 640
            windowParams.height = if (isExpanded) 680 else 400
            currentWindowManager?.updateViewLayout(rootLayout, windowParams)
        }
        val btnClose = createControlButton(displayContext, "✕") {
            stopSelf()
        }

        controlBar.addView(btnSwitchScreen)
        controlBar.addView(btnMirror)
        controlBar.addView(btnResize)
        controlBar.addView(btnClose)

        rootLayout?.addView(
            textureView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        rootLayout?.addView(
            controlBar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 70, Gravity.TOP)
        )

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        rootLayout?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowParams.x
                    initialY = windowParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    windowParams.x = initialX + (event.rawX - touchX).toInt()
                    windowParams.y = initialY + (event.rawY - touchY).toInt()
                    currentWindowManager?.updateViewLayout(rootLayout, windowParams)
                    true
                }
                else -> false
            }
        }

        currentWindowManager?.addView(rootLayout, windowParams)
    }

    private fun createControlButton(ctx: Context, title: String, onClick: () -> Unit): Button {
        return Button(ctx).apply {
            text = title
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onClick() }
        }
    }

    private fun switchMonitor() {
        val displays = displayManager.displays
        if (displays.size > 1) {
            currentDisplayIndex = if (currentDisplayIndex == 0) 1 else 0
            attachWindowToDisplay(displays[currentDisplayIndex])
        }
    }

    private fun initUsbCamera() {
        cameraHelper = CameraHelper().apply {
            setStateCallback(object : ICameraHelper.StateCallback {
                override fun onAttach(device: UsbDevice?) {
                    device?.let { selectDevice(it) }
                }

                override fun onDeviceOpen(device: UsbDevice?, isFirstOpen: Boolean) {
                    openCamera()
                }

                override fun onCameraOpen(device: UsbDevice?) {
                    // Запускаем поток и привязываем к окну
                    startPreview()
                    textureView?.surfaceTexture?.let { st ->
                        if (previewSurface == null) {
                            previewSurface = Surface(st)
                        }
                        previewSurface?.let { addSurface(it, false) }
                    }
                }

                override fun onCameraClose(device: UsbDevice?) {
                    previewSurface?.let { removeSurface(it) }
                }

                override fun onDeviceClose(device: UsbDevice?) {}
                override fun onDetach(device: UsbDevice?) {}
                override fun onCancel(device: UsbDevice?) {}
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        previewSurface?.let {
            cameraHelper?.removeSurface(it)
            it.release()
        }
        cameraHelper?.release()
        cameraHelper = null
        rootLayout?.let {
            try { currentWindowManager?.removeView(it) } catch (_: Exception) {}
        }
    }
}
