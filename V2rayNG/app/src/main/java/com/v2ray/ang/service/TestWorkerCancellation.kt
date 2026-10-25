package com.v2ray.ang.service

/** Keeps a public-server test from cancelling an unrelated test request. */
internal fun shouldCancelTestRequest(workerRequestId: String, requestedRequestId: String): Boolean =
    requestedRequestId.isBlank() || workerRequestId == requestedRequestId
