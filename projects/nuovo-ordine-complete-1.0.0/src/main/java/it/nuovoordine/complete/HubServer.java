package it.nuovoordine.complete;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

final class HubServer {
    private HttpServer server;
    void start(String bind,int port,String cors,Supplier<String> json){stop();try{server=HttpServer.create(new InetSocketAddress(bind,port),0);server.createContext("/api/all",ex->reply(ex,json.get(),cors));server.createContext("/api/status",ex->reply(ex,json.get(),cors));server.createContext("/health",ex->reply(ex,"{\"ok\":true}",cors));server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"NuovoOrdineHub");t.setDaemon(true);return t;}));server.start();}catch(Exception e){server=null;}}
    void stop(){if(server!=null){server.stop(0);server=null;}}
    private static void reply(HttpExchange ex,String body,String cors)throws IOException{byte[] b=body.getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");ex.getResponseHeaders().set("Cache-Control","no-store");ex.getResponseHeaders().set("Access-Control-Allow-Origin",cors);ex.sendResponseHeaders(200,b.length);try(OutputStream o=ex.getResponseBody()){o.write(b);}}
}
