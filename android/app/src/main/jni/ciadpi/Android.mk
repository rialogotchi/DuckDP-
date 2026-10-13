LOCAL_PATH := $(call my-dir)
BYEDPI := $(LOCAL_PATH)/../byedpi

include $(CLEAR_VARS)
LOCAL_MODULE := ciadpi-jni
BYEDPI_SRC := $(filter-out %/win_service.c,$(wildcard $(BYEDPI)/*.c))
LOCAL_SRC_FILES := jni_main.c $(BYEDPI_SRC:$(LOCAL_PATH)/%=%)
LOCAL_C_INCLUDES := $(BYEDPI)
# ByeDPI's main() becomes an ordinary function we call from JNI
LOCAL_CFLAGS := -O2 -D_DEFAULT_SOURCE -Dmain=ciadpi_main -Wno-unused-parameter
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
