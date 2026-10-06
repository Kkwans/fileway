package tailnet

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestUnapprovedRouteCannotUseReachableLAN(t *testing.T) {
	var hits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits.Add(1); w.WriteHeader(200) }))
	defer server.Close()
	res, err := http.Get(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	for _, c := range []struct{ via, owned bool }{{false, true}, {true, false}, {false, false}} {
		called := false
		d := routeDialer{
			plan: func(context.Context, string, string) (netip.AddrPort, bool, error) {
				return netip.MustParseAddrPort("127.0.0.1:8080"), c.via, nil
			},
			owned: func(netip.Addr) bool { return c.owned },
			tcp: func(context.Context, netip.AddrPort) (net.Conn, error) {
				called = true
				return nil, errors.New("must not dial")
			},
		}
		client := &http.Client{Transport: &http.Transport{DialContext: d.dial}}
		if response, err := client.Get(server.URL); err == nil {
			response.Body.Close()
			t.Fatal("unapproved destination accepted")
		}
		if called {
			t.Fatal("unapproved address reached netstack")
		}
	}
	if hits.Load() != 1 {
		t.Fatal("system fallback reached the LAN server", hits.Load())
	}
}

func TestResolvedIPStaysInNetstackAndPreservesTLSHost(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	certificate := &x509.Certificate{SerialNumber: big.NewInt(1), DNSNames: []string{"nas.example.test"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true, IsCA: true}
	der, err := x509.CreateCertificate(rand.Reader, certificate, certificate, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	leaf, _ := x509.ParseCertificate(der)
	roots := x509.NewCertPool()
	roots.AddCert(leaf)
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.Host, "nas.example.test:") || r.TLS.ServerName != "nas.example.test" {
			t.Error("HTTP/TLS host changed", r.Host, r.TLS.ServerName)
		}
		w.WriteHeader(204)
	}))
	server.TLS = &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}}
	server.StartTLS()
	defer server.Close()
	u, _ := url.Parse(server.URL)
	_, port, _ := net.SplitHostPort(u.Host)
	target := netip.MustParseAddrPort("100.100.100.50:" + port)
	plans, dials := 0, 0
	d := routeDialer{
		plan: func(_ context.Context, network, address string) (netip.AddrPort, bool, error) {
			plans++
			if address != net.JoinHostPort("nas.example.test", port) {
				t.Error("original domain not classified", address)
			}
			return target, true, nil
		},
		owned: func(ip netip.Addr) bool { return ip == target.Addr() },
		tcp: func(ctx context.Context, received netip.AddrPort) (net.Conn, error) {
			dials++
			if received != target {
				t.Error("resolved destination changed", received)
			}
			// A controlled local TLS fixture stands in for the netstack socket;
			// no real node is enrolled or external service contacted by this test.
			return (&net.Dialer{}).DialContext(ctx, "tcp", u.Host)
		},
	}
	transport := &http.Transport{DialContext: d.dial, TLSClientConfig: &tls.Config{RootCAs: roots}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport}
	res, err := client.Get("https://" + net.JoinHostPort("nas.example.test", port) + "/media")
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 204 || plans != 1 || dials != 1 {
		t.Fatal(res.StatusCode, plans, dials)
	}
}

func TestCancelledDialDoesNotResolveOrConnect(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	called := false
	d := routeDialer{plan: func(context.Context, string, string) (netip.AddrPort, bool, error) {
		called = true
		return netip.AddrPort{}, false, nil
	}}
	_, err := d.dial(ctx, "tcp", "nas.example.test:80")
	if !errors.Is(err, context.Canceled) || called {
		t.Fatal(err, called)
	}
}
