#include <stdio.h>
#include <stdlib.h>
#include "protocol.h"
static unsigned checks;
#define CHECK(x) do{++checks;if(!(x)){fprintf(stderr,"FAIL %s:%d %s\n",__FILE__,__LINE__,#x);exit(1);}}while(0)
static void u32(unsigned char *p,uint32_t n){p[0]=(unsigned char)(n>>24);p[1]=(unsigned char)(n>>16);p[2]=(unsigned char)(n>>8);p[3]=(unsigned char)n;}
static void packet(unsigned char *b,uint32_t session,uint32_t sequence,uint64_t ns,int32_t dx,int32_t dy,int32_t buttons,int32_t wheel,uint32_t flags){
    memcpy(b,"GLX1",4);u32(b+4,session);u32(b+8,sequence);u32(b+12,(uint32_t)(ns>>32));u32(b+16,(uint32_t)ns);
    u32(b+20,(uint32_t)dx);u32(b+24,(uint32_t)dy);u32(b+28,(uint32_t)buttons);u32(b+32,(uint32_t)wheel);u32(b+36,flags);
}
int main(void){
    unsigned char b[40];GlxPacket p;GlxStream s={0};
    packet(b,123,0,1000000000ULL,-127,127,7,-1,0);
    CHECK(glx_decode(b,40,&p));CHECK(p.session==123);CHECK(p.sequence==0);CHECK(p.timestamp_ns==1000000000ULL);
    CHECK(p.dx==-127 && p.dy==127 && p.buttons==7 && p.wheel==-1);
    CHECK(!glx_decode(b,39,&p));CHECK(!glx_decode(b,41,&p));b[0]='?';CHECK(!glx_decode(b,40,&p));
    packet(b,123,0,1000000000ULL,32768,0,0,0,0);CHECK(!glx_decode(b,40,&p));
    packet(b,123,0,1000000000ULL,0,-32768,0,0,0);CHECK(!glx_decode(b,40,&p));
    packet(b,123,0,1000000000ULL,0,0,8,0,0);CHECK(!glx_decode(b,40,&p));
    packet(b,123,0,1000000000ULL,0,0,0,128,0);CHECK(!glx_decode(b,40,&p));
    packet(b,123,0,1000000000ULL,0,0,0,0,4);CHECK(!glx_decode(b,40,&p));
    packet(b,123,0,1000000000ULL,0,0,1,0,GLX_RESET);CHECK(!glx_decode(b,40,&p));
    packet(b,123,0,1000000000ULL,0,0,0,0,GLX_RESET);CHECK(glx_decode(b,40,&p));CHECK(glx_accept(&s,&p,10)==2);
    CHECK(glx_accept(&s,&p,11)==0);
    p.sequence=1;p.timestamp_ns+=10000000ULL;p.buttons=1;CHECK(glx_accept(&s,&p,20)==1);CHECK(s.buttons==1);
    p.sequence=0;CHECK(!glx_accept(&s,&p,21));
    p.sequence=2;p.timestamp_ns=900000000ULL;CHECK(!glx_accept(&s,&p,22));
    p.timestamp_ns=1020000000ULL;CHECK(!glx_accept(&s,&p,400)); /* delayed >250ms */
    CHECK(!glx_timeout(&s,519));CHECK(glx_timeout(&s,520));CHECK(s.buttons==0 && !s.active);CHECK(!glx_timeout(&s,521));
    p.sequence=3;p.timestamp_ns=1600000000ULL;CHECK(glx_accept(&s,&p,610)==1);CHECK(s.active && s.buttons==1);
    p.session=124;p.sequence=0;p.flags=0;CHECK(!glx_accept(&s,&p,611));
    p.flags=GLX_RESET;p.buttons=0;CHECK(glx_accept(&s,&p,612)==2);CHECK(s.buttons==0);
    p.session=123;CHECK(!glx_accept(&s,&p,613)); /* retired session cannot take back over */
    p.session=124;p.sequence=0xffffffffu;p.timestamp_ns+=1000000ULL;CHECK(!glx_accept(&s,&p,614));
    s.sequence=0xfffffffeu;p.sequence=0xffffffffu;CHECK(glx_accept(&s,&p,614)==1);
    p.sequence=0;p.timestamp_ns+=1000000ULL;CHECK(glx_accept(&s,&p,615)==1);
    p.sequence=20;p.timestamp_ns+=1000000ULL;CHECK(glx_accept(&s,&p,616)==1); /* lost reports do not block later state */
    CHECK(s.accepted==7);CHECK(s.rejected==7);
    printf("PASS: %u protocol, limit, order, stale, session, wrap and timeout checks\n",checks);return 0;
}
