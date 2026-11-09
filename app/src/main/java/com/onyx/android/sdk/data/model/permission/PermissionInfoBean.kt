package com.onyx.android.sdk.data.model.permission

import android.os.Parcel
import android.os.Parcelable

/**
 * 与文石 ksync 语音服务 AIDL 对齐的最小 Parcelable 镜像。
 * 包名/字段顺序必须与服务端一致，否则跨进程反序列化会失败。
 */
class PermissionInfoBean : Parcelable {
    var level: Int = 0
    var target: String? = null
    var tag: String? = null
    var engine: String? = null
    var enable: Boolean = false
    var name: String? = null
    var allTimes: Long = 0
    var dayTimes: Long = 0
    var usedAllTimes: Long = 0
    var usedDayTimes: Long = 0
    var updateAt: Long = 0
    var isWarning: Boolean = false

    constructor()

    constructor(parcel: Parcel) {
        level = parcel.readInt()
        target = parcel.readString()
        tag = parcel.readString()
        engine = parcel.readString()
        enable = parcel.readByte() != 0.toByte()
        name = parcel.readString()
        allTimes = parcel.readLong()
        dayTimes = parcel.readLong()
        usedAllTimes = parcel.readLong()
        usedDayTimes = parcel.readLong()
        updateAt = parcel.readLong()
        isWarning = parcel.readByte() != 0.toByte()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(level)
        parcel.writeString(target)
        parcel.writeString(tag)
        parcel.writeString(engine)
        parcel.writeByte(if (enable) 1.toByte() else 0.toByte())
        parcel.writeString(name)
        parcel.writeLong(allTimes)
        parcel.writeLong(dayTimes)
        parcel.writeLong(usedAllTimes)
        parcel.writeLong(usedDayTimes)
        parcel.writeLong(updateAt)
        parcel.writeByte(if (isWarning) 1.toByte() else 0.toByte())
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<PermissionInfoBean> {
            override fun createFromParcel(parcel: Parcel): PermissionInfoBean = PermissionInfoBean(parcel)
            override fun newArray(size: Int): Array<PermissionInfoBean?> = arrayOfNulls(size)
        }
    }
}
