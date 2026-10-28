package com.lixiang.usbcam

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper

class UvcFloatingService : Service() {

    private lateinit var displayManager: DisplayManager
    private lateinit var prefs: SharedPreferences
    private var currentDisplayIndex = 0
    private var currentWindowManager: WindowManager? = null
    private var rootLayout: LinearLayout? = null
    private var surfaceView: SurfaceView? = null
    private var statusText: TextView? = null
    private var brightnessPanel: LinearLayout? = null
    private var seekBrightness: SeekBar? = null
    private var seekGain: SeekBar? = null
    private lateinit var windowParams: WindowManager.LayoutParams

    private var cameraHelper: ICameraHelper? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isExpanded = false
    private var isBrightnessVisible = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("cam_settings", Context.MODE_PRIVATE)
        startForegroundNotification()

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays

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

    private fun updateStatus(msg: String, hideText: Boolean = false) {
        mainHandler.post {
            statusText?.text = msg
            statusText?.visibility = if (hideText) View.GONE else View.VISIBLE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachWindowToDisplay(targetDisplay: Display) {
        surfaceView?.holder?.surface?.let {
            if (it.isValid) cameraHelper?.removeSurface(it)
        }
        rootLayout?.let {
            try { currentWindowManager?.removeView(it) } catch (_: Exception) {}
        }

        val displayContext = createDisplayContext(targetDisplay)
        currentWindowManager = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val width = if (isExpanded) 1280 else 780
        val height = if (isExpanded) 780 else 500

        windowParams = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.RGBA_8888
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 120
            y = 120
        }

        rootLayout = LinearLayout(displayContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF1A1A1A"))
            setPadding(4, 4, 4, 4)
        }

        // Верхняя панель кнопок
        val controlBar = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#FF2C2C2C"))
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(12, 0, 12, 0)
        }

        val titleLabel = TextView(displayContext).apply {
            text = "Камера (Экран ${currentDisplayIndex + 1})"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnLight = createControlButton(displayContext, "☀ Свет") {
            isBrightnessVisible = !isBrightnessVisible
            brightnessPanel?.visibility = if (isBrightnessVisible) View.VISIBLE else View.GONE
        }
        val btnSwitchScreen = createControlButton(displayContext, "⇄ Экран") {
            switchMonitor()
        }
        val btnReconnect = createControlButton(displayContext, "↻") {
            checkAndConnectUsbDevice()
        }
        val btnResize = createControlButton(displayContext, "Размер") {
            isExpanded = !isExpanded
            windowParams.width = if (isExpanded) 1280 else 780
            windowParams.height = if (isExpanded) 780 else 500
            currentWindowManager?.updateViewLayout(rootLayout, windowParams)
        }
        val btnClose = createControlButton(displayContext, "✕") {
            stopSelf()
        }

        controlBar.addView(titleLabel)
        controlBar.addView(btnLight)
        controlBar.addView(btnSwitchScreen)
        controlBar.addView(btnReconnect)
        controlBar.addView(btnResize)
        controlBar.addView(btnClose)

        // Панель регулировки яркости и ISO
        brightnessPanel = LinearLayout(displayContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD222222"))
            setPadding(24, 12, 24, 12)
            visibility = if (isBrightnessVisible) View.VISIBLE else View.GONE
        }

        val savedBrightness = prefs.getInt("brightness", 80)
        val savedGain = prefs.getInt("gain", 85)

        val rowBright = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val lblBright = TextView(displayContext).apply {
            text = "Яркость:"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(150, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        seekBrightness = SeekBar(displayContext).apply {
            max = 100
            progress = savedBrightness
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        prefs.edit().putInt("brightness", progress).apply()
                        applyCameraLightSettings(progress, seekGain?.progress ?: savedGain)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        rowBright.addView(lblBright)
        rowBright.addView(seekBrightness)

        val rowGain = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 0)
        }
        val lblGain = TextView(displayContext).apply {
            text = "Ночь (ISO):"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(150, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        seekGain = SeekBar(displayContext).apply {
            max = 100
            progress = savedGain
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        prefs.edit().putInt("gain", progress).apply()
                        applyCameraLightSettings(seekBrightness?.progress ?: savedBrightness, progress)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        rowGain.addView(lblGain)
        rowGain.addView(seekGain)

        brightnessPanel?.addView(rowBright)
        brightnessPanel?.addView(rowGain)

        // Контейнер видео с жесткой минимальной высотой (предотвращает схлопывание в полоску)
        val videoContainer = FrameLayout(displayContext).apply {
            setBackgroundColor(Color.BLACK)
            minimumHeight = 400
        }

        surfaceView = SurfaceView(displayContext).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    cameraHelper?.addSurface(holder.surface, false)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    cameraHelper?.removeSurface(holder.surface)
                }
            })
        }

        statusText = TextView(displayContext).apply {
            text = "Поиск USB-камеры..."
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#AA000000"))
        }

        videoContainer.addView(
            surfaceView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        videoContainer.addView(
            statusText,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        videoContainer.addView(
            brightnessPanel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        )

        rootLayout?.addView(
            controlBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80)
        )
        rootLayout?.addView(
            videoContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // Перетаскивание окна за шапку
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        controlBar.setOnTouchListener { _, event ->
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

    private fun applyCameraLightSettings(brightnessPercent: Int, gainPercent: Int) {
        try {
            val ctrl = cameraHelper?.uvcControl ?: return
            try { ctrl.brightnessPercent = brightnessPercent } catch (_: Exception) {}
            try { ctrl.gainPercent = gainPercent } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    private fun createControlButton(ctx: Context, title: String, onClick: () -> Unit): Button {
        return Button(ctx).apply {
            text = title
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF444444"))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 64)
            lp.setMargins(6, 8, 6, 8)
            layoutParams = lp
            setPadding(16, 0, 16, 0)
            setOnClickListener { onClick() }
        }
    }

    private fun switchMonitor() {
        val displays = displayManager.displays
        if (displays.size > 1) {
            currentDisplayIndex = if (currentDisplayIndex == 0) 1 else 0
            attachWindowToDisplay(displays[currentDisplayIndex])
        } else {
            updateStatus("Второй монитор не найден", false)
        }
    }

    private fun checkAndConnectUsbDevice() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            updateStatus("USB-устройства не найдены.\nПроверьте порт в подлокотнике!", false)
            return
        }
        val list = cameraHelper?.deviceList
        if (!list.isNullOrEmpty()) {
            updateStatus("Подключение к ${list[0].productName ?: "USB-камере"}...", false)
            cameraHelper?.selectDevice(list[0])
        } else {
            updateStatus("Найдено USB (${deviceList.size}), но не UVC-камера", false)
        }
    }

    private fun initUsbCamera() {
        cameraHelper = CameraHelper().apply {
            setStateCallback(object : ICameraHelper.StateCallback {
                override fun onAttach(device: UsbDevice?) {
                    updateStatus("Камера подключена, запрос прав...", false)
                    device?.let { selectDevice(it) }
                }

                override fun onDeviceOpen(device: UsbDevice?, isFirstOpen: Boolean) {
                    updateStatus("Открытие видеопотока...", false)
                    openCamera()
                }

                override fun onCameraOpen(device: UsbDevice?) {
                    startPreview()
                    surfaceView?.holder?.surface?.let {
                        if (it.isValid) addSurface(it, false)
                    }
                    updateStatus("", true)

                    mainHandler.postDelayed({
                        val b = prefs.getInt("brightness", 80)
                        val g = prefs.getInt("gain", 85)
                        applyCameraLightSettings(b, g)
                    }, 300)
                }

                override fun onCameraClose(device: UsbDevice?) {
                    surfaceView?.holder?.surface?.let { removeSurface(it) }
                    updateStatus("Камера отключена", false)
                }

                override fun onDeviceClose(device: UsbDevice?) {}
                override fun onDetach(device: UsbDevice?) {
                    updateStatus("Камера отключена от порта", false)
                }
                override fun onCancel(device: UsbDevice?) {
                    updateStatus("Нет прав доступа к USB (нажмите ↻)", false)
                }
            })
        }
        mainHandler.postDelayed({ checkAndConnectUsbDevice() }, 500)
    }

    override fun onDestroy() {
        super.onDestroy()
        surfaceView?.holder?.surface?.let {
            cameraHelper?.removeSurface(it)
        }
        cameraHelper?.release()
        cameraHelper = null
        rootLayout?.let {
            try { currentWindowManager?.removeView(it) } catch (_: Exception) {}
        }
    }
}
