#ifndef GLX_PROTOCOL_H
#define GLX_PROTOCOL_H
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#define GLX_PORT 26761
#define GLX_PACKET_SIZE 40
#define GLX_HEARTBEAT 1u
#define GLX_RESET 2u
#define GLX_TIMEOUT_MS 500u
#define GLX_MAX_AGE_MS 250u

typedef struct {
    uint32_t session, sequence;
    uint64_t timestamp_ns;
    int32_t dx, dy, buttons, wheel;
    uint32_t flags;
} GlxPacket;
typedef struct {
    uint32_t session, sequence, buttons;
    uint64_t first_sender_ns, first_receiver_ms, last_receiver_ms;
    uint32_t retired[16];
    unsigned retired_count, retired_next;
    int initialized, active;
    uint64_t accepted, rejected;
} GlxStream;
static uint32_t glx_u32(const unsigned char *p) {
    return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3];
}
static uint64_t glx_u64(const unsigned char *p) {
    return ((uint64_t)glx_u32(p)<<32)|glx_u32(p+4);
}
static int32_t glx_i32(const unsigned char *p) {
    uint32_t u=glx_u32(p); int32_t v; memcpy(&v,&u,sizeof(v)); return v;
}
static int glx_decode(const unsigned char *b, size_t n, GlxPacket *p) {
    if (n!=GLX_PACKET_SIZE || memcmp(b,"GLX1",4)!=0) return 0;
    p->session=glx_u32(b+4); p->sequence=glx_u32(b+8);
    p->timestamp_ns=glx_u64(b+12); p->dx=glx_i32(b+20); p->dy=glx_i32(b+24);
    p->buttons=glx_i32(b+28); p->wheel=glx_i32(b+32); p->flags=glx_u32(b+36);
    if ((p->buttons&~7)!=0 || (p->flags&~3u)!=0 || p->dx < -32767 || p->dx > 32767 ||
        p->dy < -32767 || p->dy > 32767 || p->wheel < -127 || p->wheel > 127) return 0;
    if ((p->flags&GLX_RESET) && (p->dx || p->dy || p->buttons || p->wheel)) return 0;
    return 1;
}
static void glx_retire(GlxStream *s,uint32_t session) {
    s->retired[s->retired_next]=session; s->retired_next=(s->retired_next+1)%16;
    if(s->retired_count<16) ++s->retired_count;
}
/* 1 accepts a packet, 2 accepts a new session and asks caller to release old buttons. */
static int glx_accept(GlxStream *s,const GlxPacket *p,uint64_t now_ms) {
    int changed=!s->initialized || p->session!=s->session;
    if(changed) {
        unsigned i;
        for(i=0;i<s->retired_count;i++) if(s->retired[i]==p->session) { ++s->rejected; return 0; }
        if(s->active && !(p->flags&GLX_RESET)) { ++s->rejected; return 0; }
        if(s->initialized) glx_retire(s,s->session);
        s->session=p->session; s->sequence=p->sequence;
        s->first_sender_ns=p->timestamp_ns; s->first_receiver_ms=now_ms;
        s->initialized=1;
    } else {
        uint32_t advance=p->sequence-s->sequence;
        if(advance==0 || advance>=0x80000000u || p->timestamp_ns<s->first_sender_ns) { ++s->rejected; return 0; }
        /* Relative monotonic clocks: rejects a packet >250 ms behind this stream's observed baseline.
           Does not estimate one-way latency and cannot detect delay before the first packet. */
        uint64_t sender_elapsed=(p->timestamp_ns-s->first_sender_ns)/1000000u;
        uint64_t receiver_elapsed=now_ms-s->first_receiver_ms;
        if(receiver_elapsed>sender_elapsed+GLX_MAX_AGE_MS) { ++s->rejected; return 0; }
        s->sequence=p->sequence;
    }
    s->last_receiver_ms=now_ms; s->active=1; s->buttons=(uint32_t)p->buttons; ++s->accepted;
    return changed?2:1;
}
static int glx_timeout(GlxStream *s,uint64_t now_ms) {
    if(s->active && now_ms-s->last_receiver_ms>=GLX_TIMEOUT_MS) {
        s->active=0; s->buttons=0; return 1;
    }
    return 0;
}
#endif
