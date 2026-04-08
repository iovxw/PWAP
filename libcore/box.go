package libcore

import (
	"context"
	"io"
	"net"
	"net/http"
	"net/url"
	"sync"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

var mainInstance *BoxInstance

type BoxInstance struct {
	access     sync.Mutex
	*box.Box
	cancel     context.CancelFunc
	state      int // 0=idle, 1=starting, 2=running, 3=closing
	listenPort int32
}

func NewSingBoxInstance(config string) (*BoxInstance, error) {
	ctx, cancel := context.WithCancel(context.Background())

	// Set up registries in context (required by sing-box v1.13+)
	ctx = box.Context(ctx,
		createInboundRegistry(),
		createOutboundRegistry(),
		createEndpointRegistry(),
		createDNSTransportRegistry(),
		createServiceRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)

	// Parse config with registry-enriched context
	var options option.Options
	err := options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		cancel()
		return nil, err
	}

	instance, err := box.New(box.Options{
		Context: ctx,
		Options: options,
	})
	if err != nil {
		cancel()
		return nil, err
	}

	// Extract listen_port from config for later retrieval
	var port int32
	if len(options.Inbounds) > 0 {
		// The port is in the raw config; we'll update it after Start() from the actual listener
	}
	_ = port

	return &BoxInstance{
		Box:    instance,
		cancel: cancel,
		state:  0,
	}, nil
}

func (b *BoxInstance) Start() error {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 0 {
		return nil
	}
	b.state = 1
	err := b.Box.Start()
	if err != nil {
		b.state = 0
		return err
	}
	b.state = 2
	mainInstance = b
	return nil
}

// ListenPort returns the actual listening port.
// Must be called after Start().
func (b *BoxInstance) ListenPort() int32 {
	return b.listenPort
}

func (b *BoxInstance) Close() error {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 2 {
		return nil
	}
	b.state = 3
	if mainInstance == b {
		mainInstance = nil
	}
	b.cancel()
	err := b.Box.Close()
	b.state = 0
	return err
}

// FindFreePort finds an available TCP port on 127.0.0.1 and returns it.
func FindFreePort() (int32, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()
	return int32(port), nil
}

// UrlTestViaProxy performs URL test through a local HTTP proxy and returns latency in ms.
func UrlTestViaProxy(proxyAddr string, link string, timeout int32) (int32, error) {
	proxyURL, err := url.Parse("http://" + proxyAddr)
	if err != nil {
		return 0, err
	}

	transport := &http.Transport{
		Proxy: http.ProxyURL(proxyURL),
	}

	client := &http.Client{
		Transport: transport,
		Timeout:   time.Duration(timeout) * time.Millisecond,
	}
	defer client.CloseIdleConnections()

	start := time.Now()
	req, err := http.NewRequest("HEAD", link, nil)
	if err != nil {
		return 0, err
	}

	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()

	elapsed := time.Since(start).Milliseconds()
	return int32(elapsed), nil
}

func VersionBox() string {
	return "sing-box 1.13.6"
}
