package libcore

import (
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport"
	"github.com/sagernet/sing-box/dns/transport/hosts"
	"github.com/sagernet/sing-box/dns/transport/local"
	"github.com/sagernet/sing-box/dns/transport/quic"
	"github.com/sagernet/sing-box/protocol/anytls"
	"github.com/sagernet/sing-box/protocol/block"
	"github.com/sagernet/sing-box/protocol/direct"
	protocolDns "github.com/sagernet/sing-box/protocol/dns"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing-box/protocol/http"
	"github.com/sagernet/sing-box/protocol/hysteria"
	"github.com/sagernet/sing-box/protocol/hysteria2"
	"github.com/sagernet/sing-box/protocol/mixed"
	"github.com/sagernet/sing-box/protocol/shadowsocks"
	"github.com/sagernet/sing-box/protocol/shadowtls"
	"github.com/sagernet/sing-box/protocol/socks"
	"github.com/sagernet/sing-box/protocol/trojan"
	"github.com/sagernet/sing-box/protocol/tuic"
	"github.com/sagernet/sing-box/protocol/vless"
	"github.com/sagernet/sing-box/protocol/vmess"
	"github.com/sagernet/sing-box/protocol/wireguard"

	_ "github.com/sagernet/sing-box/transport/v2rayquic"
)

func createInboundRegistry() *inbound.Registry {
	registry := inbound.NewRegistry()
	direct.RegisterInbound(registry)
	socks.RegisterInbound(registry)
	http.RegisterInbound(registry)
	mixed.RegisterInbound(registry)
	return registry
}

func createOutboundRegistry() *outbound.Registry {
	registry := outbound.NewRegistry()
	direct.RegisterOutbound(registry)
	block.RegisterOutbound(registry)
	protocolDns.RegisterOutbound(registry)
	group.RegisterSelector(registry)
	group.RegisterURLTest(registry)
	socks.RegisterOutbound(registry)
	http.RegisterOutbound(registry)
	shadowsocks.RegisterOutbound(registry)
	vmess.RegisterOutbound(registry)
	trojan.RegisterOutbound(registry)
	vless.RegisterOutbound(registry)
	shadowtls.RegisterOutbound(registry)
	anytls.RegisterOutbound(registry)
	hysteria.RegisterOutbound(registry)
	hysteria2.RegisterOutbound(registry)
	tuic.RegisterOutbound(registry)
	return registry
}

func createEndpointRegistry() *endpoint.Registry {
	registry := endpoint.NewRegistry()
	wireguard.RegisterEndpoint(registry)
	return registry
}

func createDNSTransportRegistry() *dns.TransportRegistry {
	registry := dns.NewTransportRegistry()
	transport.RegisterTCP(registry)
	transport.RegisterUDP(registry)
	transport.RegisterTLS(registry)
	transport.RegisterHTTPS(registry)
	local.RegisterTransport(registry)
	hosts.RegisterTransport(registry)
	quic.RegisterTransport(registry)
	quic.RegisterHTTP3Transport(registry)
	return registry
}

func createServiceRegistry() *service.Registry {
	registry := service.NewRegistry()
	return registry
}
