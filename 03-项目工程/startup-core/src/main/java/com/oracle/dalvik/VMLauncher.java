/*
 * 本文件原样来自 ZalithLauncher2（其启动核心继承自 PojavLauncher），未做改动。
 *
 * 包名 com.oracle.dalvik 不可更改：native 侧的函数名是静态导出的
 * Java_com_oracle_dalvik_VMLauncher_launchJVM（见 jni/jre_launcher.c:171），
 * 改名会让 JNI 找不到实现。
 *
 * Vaelin Launcher 整体以 GPL-3.0 分发。
 */

package com.oracle.dalvik;

import androidx.annotation.Keep;

@Keep
public final class VMLauncher {
	private VMLauncher() {
	}
    @Keep public static native int launchJVM(String[] args);
}
