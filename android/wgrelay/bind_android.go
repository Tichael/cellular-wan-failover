//go:build android

package main

/*
#include <jni.h>
#include <android/multinetwork.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>

static inline const char* get_jstring(JNIEnv* env, jstring s) {
    if (!s) return NULL;
    return (*env)->GetStringUTFChars(env, s, NULL);
}

static inline void release_jstring(JNIEnv* env, jstring s, const char* chars) {
    if (s && chars) {
        (*env)->ReleaseStringUTFChars(env, s, chars);
    }
}

static inline int c_bind_socket_to_network(uint64_t handle, int fd) {
    if (handle == 0) return 0;
    return android_setsocknetwork((net_handle_t)handle, fd);
}

static inline void c_log_info(const char* msg) {
    __android_log_print(ANDROID_LOG_INFO, "wgrelay", "%s", msg);
}
*/
import "C"

import (
	"fmt"
	"syscall"
	"unsafe"
)

func androidLog(msg string) {
	cMsg := C.CString(msg)
	defer C.free(unsafe.Pointer(cMsg))
	C.c_log_info(cMsg)
}

func bindFdToNetwork(c syscall.RawConn, netHandle uint64) error {
	if netHandle == 0 {
		return nil
	}
	var sockErr error
	err := c.Control(func(fd uintptr) {
		rc := C.c_bind_socket_to_network(C.uint64_t(netHandle), C.int(fd))
		if rc != 0 {
			sockErr = fmt.Errorf("android_setsocknetwork error rc=%d", rc)
		}
	})
	if err != nil {
		return err
	}
	return sockErr
}

//export Java_com_example_cellularwanfailover_wireguard_WgRelay_startRelay
func Java_com_example_cellularwanfailover_wireguard_WgRelay_startRelay(
	env *C.JNIEnv,
	cls C.jclass,
	port C.jint,
	privKeyJ C.jstring,
	peerPubKeyJ C.jstring,
	netHandle C.jlong,
) C.jint {
	cPriv := C.get_jstring(env, privKeyJ)
	if cPriv != nil {
		defer C.release_jstring(env, privKeyJ, cPriv)
	}
	cPeer := C.get_jstring(env, peerPubKeyJ)
	if cPeer != nil {
		defer C.release_jstring(env, peerPubKeyJ, cPeer)
	}

	privKey := C.GoString(cPriv)
	peerKey := C.GoString(cPeer)

	err := StartRelay(int(port), privKey, peerKey, uint64(netHandle))
	if err != nil {
		androidLog(fmt.Sprintf("StartRelay error: %v", err))
		return -1
	}
	androidLog("StartRelay success")
	return 0
}

//export Java_com_example_cellularwanfailover_wireguard_WgRelay_stopRelay
func Java_com_example_cellularwanfailover_wireguard_WgRelay_stopRelay(
	env *C.JNIEnv,
	cls C.jclass,
) C.jint {
	StopRelay()
	androidLog("StopRelay called")
	return 0
}

//export Java_com_example_cellularwanfailover_wireguard_WgRelay_getBytesTransmitted
func Java_com_example_cellularwanfailover_wireguard_WgRelay_getBytesTransmitted(
	env *C.JNIEnv,
	cls C.jclass,
) C.jlong {
	return C.jlong(GetBytesTransmitted())
}

//export Java_com_example_cellularwanfailover_wireguard_WgRelay_isRunning
func Java_com_example_cellularwanfailover_wireguard_WgRelay_isRunning(
	env *C.JNIEnv,
	cls C.jclass,
) C.jboolean {
	if IsRunning() {
		return 1
	}
	return 0
}
