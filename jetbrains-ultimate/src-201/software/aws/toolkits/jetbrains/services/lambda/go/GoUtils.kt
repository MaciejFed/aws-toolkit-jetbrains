// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.lambda.go

import com.goide.dlv.DlvDebugProcess
import com.goide.dlv.DlvDisconnectOption
import com.goide.dlv.DlvRemoteVmConnection
import com.goide.execution.GoRunUtil.getBundledDlv
import com.intellij.execution.ExecutionResult
import com.intellij.xdebugger.XDebugSession
import java.io.File

// This can take a target platform, but that pulls directly from GOOS, so we have to walk back up the file tree
// either way. Goland comes with mac/window/linux dlv since it supports remote debugging, so it is always safe to
// pull the linux one
// FIX_WHEN_MIN_IS_202 remove this one in favor of the 202+ one
fun getDelve(): File = getBundledDlv()?.parentFile?.parentFile?.resolve("linux")
    ?: throw IllegalStateException("Packaged Devle debugger is not found!")

// FIX_WHEN_MIN_IS_202 remove this version
fun createDelveDebugProcess(session: XDebugSession, executionResult: ExecutionResult) =
    DlvDebugProcess(session, DlvRemoteVmConnection(DlvDisconnectOption.DETACH), executionResult, true, false)
