package fbhttp

import "testing"

func TestDecodeResourceWirePathPreservesOpaqueBytesAndLiteralNames(t *testing.T) {
	cases := map[string]string{
		"/": "/", "/a+b": "/a+b", "/100%25%20%2B%3F%23": "/100% +?#",
		"/a%252Fb": "/a%2Fb", "/%D6%D0%CE%C4/a%5Cb": "/\xd6\xd0\xce\xc4/a\\b",
		"/Folder/%20%20/": "/Folder/  ",
	}
	for wire, expected := range cases {
		actual, err := decodeResourceWirePath(wire)
		if err != nil || actual != expected {
			t.Errorf("decode %q = %q, %v; want %q", wire, actual, err, expected)
		}
	}
	for _, wire := range []string{"", "relative", "//host/file", "/bad%", "/bad%GG", "/a%2Fb", "/bad%00", "/file?query", "/file#fragment", "/raw space", "/中文"} {
		if value, err := decodeResourceWirePath(wire); err == nil {
			t.Errorf("accepted invalid wire path %q as %q", wire, value)
		}
	}
}
