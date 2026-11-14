package com.movtery.zalithlauncher.utils.device

import android.content.Context
import android.content.pm.PackageManager

/**
 * [Maou] Pemeriksaan cepat (tanpa native, tanpa dialog) apakah HP benar-benar bisa menjalankan
 * backend Vulkan Minecraft 26.2+ yang membutuhkan Vulkan 1.2.
 *
 * HP lama (mis. Oppo A3s, Android 8.1) tidak mendeklarasikan Vulkan 1.2 sehingga hasilnya false;
 * pada HP seperti itu game HARUS dijalankan dengan OpenGL (lewat MobileGlues/LTW/dll).
 */
object VulkanHardware {
    /** Vulkan 1.2 dalam penyandian android.hardware.vulkan.version: (mayor shl 22) or (minor shl 12) */
    private const val VULKAN_1_2 = (1 shl 22) or (2 shl 12)

    fun supportsVulkan12(context: Context): Boolean = runCatching {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_2)
    }.getOrDefault(false)
}
