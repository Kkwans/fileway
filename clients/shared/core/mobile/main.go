package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"github.com/Kkwans/nas-file-browser-client/core/bridge"
	"unsafe"
)

var engine bridge.Engine

//export nfb_call
func nfb_call(data *C.char, length C.int) (output *C.char) {
	defer func() {
		if recover() != nil {
			output = C.CString(`{"ok":false,"error":"native bridge failure"}`)
		}
	}()
	if data == nil || length < 0 || length > 15<<20 {
		return C.CString(`{"ok":false,"error":"invalid command size"}`)
	}
	return C.CString(string(engine.Call(C.GoBytes(unsafe.Pointer(data), length))))
}

//export nfb_free
func nfb_free(data *C.char) { C.free(unsafe.Pointer(data)) }

func main() {}
