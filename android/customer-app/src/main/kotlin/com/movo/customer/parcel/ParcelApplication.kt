package com.movo.customer.parcel

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ParcelApplication : Application() {
    override fun onCreate() { super.onCreate(); com.movo.customer.parcel.push.configureFirebase(this) }
}
