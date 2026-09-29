import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import jdk.internal.org.objectweb.asm.*;

public class Patch implements Opcodes {
 static final String OWNER="com/cicciook/lootrmoreloot/LootOpenEvents", ENTRY=OWNER+"$Entry", ED="L"+ENTRY+";";
 static Object fix(Object value) {
  if (!(value instanceof String)) return value;
  String s=((String)value).replace("lootr_more_loot_injected_1110","lootr_more_loot_injected_1112").replace("lootr_more_loot_seen_1110","lootr_more_loot_seen_1112");
  if(s.startsWith("{")) s=s.replace("\\\"","\"");
  return s;
 }
 static void array(MethodVisitor mv,String field,Path input) throws IOException {
  List<String> lines=Files.readAllLines(input);
  mv.visitLdcInsn(lines.size());mv.visitTypeInsn(ANEWARRAY,ENTRY);
  for(int i=0;i<lines.size();i++) {
   String[] e=lines.get(i).split("\t",-1);
   mv.visitInsn(DUP);mv.visitLdcInsn(i);mv.visitLdcInsn(e[0]);
   for(int k=1;k<4;k++)mv.visitLdcInsn(Integer.parseInt(e[k]));
   if(e[4].isEmpty())mv.visitMethodInsn(INVOKESTATIC,OWNER,"e","(Ljava/lang/String;III)"+ED,false);
   else {mv.visitLdcInsn(e[4]);mv.visitMethodInsn(INVOKESTATIC,OWNER,"n","(Ljava/lang/String;IIILjava/lang/String;)"+ED,false);}
   mv.visitInsn(AASTORE);
  }
  mv.visitFieldInsn(PUTSTATIC,OWNER,field,"["+ED);
 }
 public static void main(String[] args)throws Exception {
  Path work=Path.of(args[2]);
  try(ZipFile in=new ZipFile(args[0]);ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(Path.of(args[1])))) {
   for(ZipEntry ze:Collections.list(in.entries())) {
    byte[] bytes=in.getInputStream(ze).readAllBytes();String path=ze.getName();
    if(path.equals(OWNER+".class")) {
     ClassReader cr=new ClassReader(bytes);ClassWriter cw=new ClassWriter(ClassWriter.COMPUTE_MAXS);
     cr.accept(new ClassVisitor(ASM8,cw){
      public FieldVisitor visitField(int access,String name,String desc,String sig,Object val){return super.visitField(access,name,desc,sig,fix(val));}
      public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex){
       return new MethodVisitor(ASM8,super.visitMethod(access,name,desc,sig,ex)){
        public void visitLdcInsn(Object value){
         Object v=fix(value);
         if(name.equals("pickSurvivalArmor") && v instanceof String){
          v=switch((String)v){case "plate","reaper","exo_heavy" -> "__no_armor_exclusion__";case "recruit" -> "recluit";case "ghillie" -> "guillie";default -> v;};
         }
         if(name.equals("injectBalancedLoot") && v.equals(Double.valueOf(.06)))v=.12;
         super.visitLdcInsn(v);
        }
        public void visitInvokeDynamicInsn(String n,String d,Handle h,Object... a){for(int i=0;i<a.length;i++)a[i]=fix(a[i]);super.visitInvokeDynamicInsn(n,d,h,a);}
        public void visitInsn(int opcode){
         if(name.equals("<clinit>") && opcode==RETURN)try {array(this.mv,"WEAPONS",work.resolve("WEAPONS.tsv"));array(this.mv,"AMMO",work.resolve("AMMO.tsv"));}catch(IOException e){throw new UncheckedIOException(e);}
         super.visitInsn(opcode);
        }
       };
      }
     },0);bytes=cw.toByteArray();
    } else if(path.endsWith("balanced_structure_loot.json"))bytes=Files.readAllBytes(work.resolve("loot.json"));
    else if(path.equals("META-INF/mods.toml"))bytes=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace("version=\"1.1.10\"","version=\"1.1.11\"").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    else if(path.equals("README.txt"))bytes=Files.readAllBytes(work.resolve("README.txt"));
    out.putNextEntry(new ZipEntry(path));out.write(bytes);out.closeEntry();
   }
   for(String name:List.of("Patch.java","build_catalog.py","WEAPONS.tsv","AMMO.tsv","catalog-report.json")){
    out.putNextEntry(new ZipEntry("META-INF/loot-update-source/"+name));out.write(Files.readAllBytes(work.resolve(name)));out.closeEntry();
   }
  }
 }
}
