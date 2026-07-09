package com.hiveaxyl.sdk

interface HiveAxylCallback<T> {
    fun onSuccess(value: T)
    fun onError(error: Throwable)
}
