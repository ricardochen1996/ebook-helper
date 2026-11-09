package com.onyx.android.sdk.speech.client.service

import android.os.Parcel
import android.os.Parcelable
import com.onyx.android.sdk.data.model.permission.PermissionInfoBean

/**
 * 与文石 ksync 语音服务回调 AIDL 对齐的最小 Parcelable 镜像。
 */
class SpeechOutputArgs(var eventType: Int) : Parcelable {
    var recResults: List<String>? = null
    var permissionBean: PermissionInfoBean? = null
    var volumePercent: Int = -1
    var volume: Int = -1
    var progress: Int = 0
    var errorCode: Int = -1
    var content: String? = null

    constructor(parcel: Parcel) : this(parcel.readInt()) {
        recResults = parcel.createStringArrayList()
        permissionBean = parcel.readTypedObject(PermissionInfoBean.CREATOR)
        volumePercent = parcel.readInt()
        volume = parcel.readInt()
        progress = parcel.readInt()
        errorCode = parcel.readInt()
        content = parcel.readString()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(eventType)
        parcel.writeStringList(recResults)
        parcel.writeTypedObject(permissionBean, flags)
        parcel.writeInt(volumePercent)
        parcel.writeInt(volume)
        parcel.writeInt(progress)
        parcel.writeInt(errorCode)
        parcel.writeString(content)
    }

    override fun describeContents(): Int = 0

    companion object {
        const val EVENT_ASR_PARTIAL = 0
        const val EVENT_ASR_FINISH = 1
        const val EVENT_ASR_ERROR = 2
        const val EVENT_ASR_VOLUME = 3
        const val EVENT_ASR_LONG_SPEECH = 4
        const val EVENT_ASR_EXIT = 5
        const val EVENT_REC_PROGRESS = 6
        const val EVENT_PERMISSION_INFO = 7
        const val EVENT_PERMISSION_ERROR = 8
        const val EVENT_ENGINE_AUTH_ERROR = 9
        const val EVENT_COMMON_CONFIG = 10
        const val EVENT_ASR_STATE = 11

        @JvmField
        val CREATOR = object : Parcelable.Creator<SpeechOutputArgs> {
            override fun createFromParcel(parcel: Parcel): SpeechOutputArgs = SpeechOutputArgs(parcel)
            override fun newArray(size: Int): Array<SpeechOutputArgs?> = arrayOfNulls(size)
        }
    }
}
