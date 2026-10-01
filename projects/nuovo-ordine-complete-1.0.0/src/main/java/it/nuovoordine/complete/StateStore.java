package it.nuovoordine.complete;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

final class StateStore {
    private final Path file;
    StateStore(Path file){this.file=file;}
    Models.State load(){
        if(!Files.isRegularFile(file)) return new Models.State();
        try(InputStream in=new GZIPInputStream(Files.newInputStream(file)); ObjectInputStream o=new ObjectInputStream(in)){
            Object x=o.readObject(); if(x instanceof Models.State s) return s;
        }catch(Exception e){try{Files.copy(file,file.resolveSibling(file.getFileName()+".corrupt-"+System.currentTimeMillis()),StandardCopyOption.REPLACE_EXISTING);}catch(Exception ignored){}}
        return new Models.State();
    }
    synchronized void save(Models.State state) throws IOException {
        if(file.getParent()!=null) Files.createDirectories(file.getParent()); Path tmp=file.resolveSibling(file.getFileName()+".tmp");
        try(OutputStream out=new GZIPOutputStream(Files.newOutputStream(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)); ObjectOutputStream o=new ObjectOutputStream(out)){o.writeObject(state);}
        try{Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
    }
}
