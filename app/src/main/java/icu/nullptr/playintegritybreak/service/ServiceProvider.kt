package icu.nullptr.playintegritybreak.service

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import icu.nullptr.playintegritybreak.common.Constants
import org.frknkrc44.pib_oss.common.BuildConfig

class ServiceProvider : ContentProvider() {

    override fun onCreate() = false

    override fun query(p0: Uri, p1: Array<out String>?, p2: String?, p3: Array<out String>?, p4: String?) = null

    override fun getType(p0: Uri) = null

    override fun insert(p0: Uri, p1: ContentValues?) = null

    override fun delete(p0: Uri, p1: String?, p2: Array<out String>?) = 0

    override fun update(p0: Uri, p1: ContentValues?, p2: String?, p3: Array<out String>?) = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val sourcePkg = callingPackage ?: return null
        val allowedCallers = setOf(
            Constants.ANDROID_PACKAGE_NAME,
            Constants.VENDING_PACKAGE_NAME,
            BuildConfig.APP_PACKAGE_NAME,
        )
        if (sourcePkg !in allowedCallers) return null

        val binder = extras?.getBinder("binder") ?: return null
        ServiceClient.linkService(binder)
        return Bundle()
    }
}
