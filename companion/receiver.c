#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <windows.h>
#include <ws2tcpip.h>
#include <stdio.h>
#include <stdlib.h>
#include <signal.h>
#include "protocol.h"

static volatile LONG running=1;
static uint32_t held;
static BOOL WINAPI stop_handler(DWORD type) {
    if(type==CTRL_C_EVENT || type==CTRL_BREAK_EVENT || type==CTRL_CLOSE_EVENT) {
        InterlockedExchange(&running,0); return TRUE;
    }
    return FALSE;
}
static int inject(int dx,int dy,uint32_t buttons,int wheel) {
    INPUT in; DWORD flags=0; unsigned i;
    static const DWORD down[]={MOUSEEVENTF_LEFTDOWN,MOUSEEVENTF_RIGHTDOWN,MOUSEEVENTF_MIDDLEDOWN};
    static const DWORD up[]={MOUSEEVENTF_LEFTUP,MOUSEEVENTF_RIGHTUP,MOUSEEVENTF_MIDDLEUP};
    if(dx||dy) flags|=MOUSEEVENTF_MOVE;
    for(i=0;i<3;i++) {
        uint32_t bit=1u<<i;
        if((buttons&bit)!=(held&bit)) flags|=(buttons&bit)?down[i]:up[i];
    }
    if(wheel) flags|=MOUSEEVENTF_WHEEL;
    if(!flags) return 1;
    ZeroMemory(&in,sizeof(in)); in.type=INPUT_MOUSE;
    in.mi.dx=dx; in.mi.dy=dy; in.mi.dwFlags=flags;
    in.mi.mouseData=(DWORD)(wheel*WHEEL_DELTA);
    if(SendInput(1,&in,sizeof(in))!=1) return 0;
    held=buttons; return 1;
}
static void usage(void) {
    puts("GyroLab X Windows receiver\nUsage: GyroLabReceiver.exe [--port 26761] [--log reports.csv]\n"
         "Listens on local UDP; allow the firewall prompt on Private networks only.\n"
         "Enter this PC's IPv4 address in GyroLab X. Ctrl+C stops and releases held buttons.\n"
         "Use only on a trusted local network. No pairing or authentication.\n"
         "SendInput may be rejected by elevated apps or games. HID is the primary transport.");
}
int main(int argc,char **argv) {
    WSADATA wsa; SOCKET sock=INVALID_SOCKET; struct sockaddr_in address,peer,pinned;
    GlxStream stream={0}; unsigned char buffer[2048]; FILE *log=NULL;
    unsigned port=GLX_PORT; int pinned_valid=0,exit_code=0,i; uint64_t last_print=0,failures=0;
    for(i=1;i<argc;i++) {
        if(!strcmp(argv[i],"--help")){usage();return 0;}
        else if(!strcmp(argv[i],"--port") && i+1<argc) {char *end; unsigned long x=strtoul(argv[++i],&end,10);if(*end||x==0||x>65535){fputs("Invalid port\n",stderr);return 2;}port=(unsigned)x;}
        else if(!strcmp(argv[i],"--log") && i+1<argc) {log=fopen(argv[++i],"w");if(!log){perror("log");return 2;}}
        else {usage();return 2;}
    }
    if(log) fputs("receiver_ms,sender_ns,session,sequence,dx,dy,buttons,wheel,cursor_x,cursor_y,injected\n",log);
    if(WSAStartup(MAKEWORD(2,2),&wsa)!=0) {fputs("WSAStartup failed\n",stderr);exit_code=1;goto cleanup;}
    sock=socket(AF_INET,SOCK_DGRAM,IPPROTO_UDP);
    if(sock==INVALID_SOCKET){fprintf(stderr,"socket error %d\n",WSAGetLastError());exit_code=1;goto cleanup;}
    ZeroMemory(&address,sizeof(address)); address.sin_family=AF_INET;
    address.sin_addr.s_addr=htonl(INADDR_ANY); address.sin_port=htons((u_short)port);
    if(bind(sock,(struct sockaddr*)&address,sizeof(address))!=0){fprintf(stderr,"Cannot bind UDP %u: %d\n",port,WSAGetLastError());exit_code=1;goto cleanup;}
    SetConsoleCtrlHandler(stop_handler,TRUE); usage();printf("Listening on UDP %u. Waiting for phone...\n",port);
    while(InterlockedCompareExchange(&running,1,1)) {
        fd_set readable; struct timeval wait={0,50000}; int selected;
        uint64_t now=GetTickCount64();
        if(glx_timeout(&stream,now)) {if(!inject(0,0,0,0))++failures;puts("Phone heartbeat timed out: buttons released.");}
        FD_ZERO(&readable);FD_SET(sock,&readable);selected=select(0,&readable,NULL,NULL,&wait);
        if(selected==SOCKET_ERROR){fprintf(stderr,"select error %d\n",WSAGetLastError());exit_code=1;break;}
        if(selected>0) {
            int length=sizeof(peer),n=recvfrom(sock,(char*)buffer,sizeof(buffer),0,(struct sockaddr*)&peer,&length);
            GlxPacket p; int accepted,ok; POINT cursor={0,0};
            if(n<0 || !glx_decode(buffer,(size_t)n,&p)){++stream.rejected;continue;}
            now=GetTickCount64();
            if(pinned_valid && stream.active && peer.sin_addr.s_addr!=pinned.sin_addr.s_addr) {++stream.rejected;continue;}
            accepted=glx_accept(&stream,&p,now); if(!accepted)continue;
            if(accepted==2) {if(!inject(0,0,0,0))++failures;pinned=peer;pinned_valid=1;printf("Session %lu connected.\n",(unsigned long)p.session);}
            if(p.flags&GLX_RESET) {ok=inject(0,0,0,0);}
            else ok=inject(p.dx,p.dy,(uint32_t)p.buttons,p.wheel);
            if(!ok)++failures;
            GetCursorPos(&cursor);
            if(log) fprintf(log,"%llu,%llu,%lu,%lu,%ld,%ld,%ld,%ld,%ld,%ld,%d\n",
                (unsigned long long)now,(unsigned long long)p.timestamp_ns,(unsigned long)p.session,(unsigned long)p.sequence,
                (long)p.dx,(long)p.dy,(long)p.buttons,(long)p.wheel,(long)cursor.x,(long)cursor.y,ok);
        }
        now=GetTickCount64();
        if(now-last_print>=5000 && stream.initialized) {
            printf("%s accepted=%llu rejected=%llu injection_failures=%llu buttons=%lu\n",
                stream.active?"Active":"Idle",(unsigned long long)stream.accepted,(unsigned long long)stream.rejected,
                (unsigned long long)failures,(unsigned long)held);
            if(log)fflush(log);last_print=now;
        }
    }
cleanup:
    /* Release even when the most recent injection failed. */
    inject(0,0,0,0);
    if(sock!=INVALID_SOCKET)closesocket(sock);WSACleanup();if(log)fclose(log);
    return exit_code;
}
