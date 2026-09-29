"""Build and verify Armeria Browser with Java 17.

python3 build.py --deps /directory/containing/compile-jars [--test]
Compile jars: Forge 47.x universal, javafmllanguage, fmlcore, fmlloader,
eventbus, mergetool (Dist), Brigadier, Gson, Netty buffer/common and MCEF 2.1.6.
Minecraft, TACZ, Superb Warfare and LesRaisins are accessed through their runtime APIs
and do not need to be included in this compilation classpath.
"""
import argparse, os, pathlib, shutil, subprocess, zipfile

p=argparse.ArgumentParser();p.add_argument('--deps',required=True);p.add_argument('--test',action='store_true')
a=p.parse_args();root=pathlib.Path(__file__).resolve().parent;build=root/'build'
classes=build/'classes';shutil.rmtree(classes,ignore_errors=True);classes.mkdir(parents=True)
cp=os.pathsep.join(str(x.resolve()) for x in pathlib.Path(a.deps).glob('*.jar'))
compiler=['java','-m','jdk.compiler/com.sun.tools.javac.Main','--release','17']
subprocess.run(compiler+['-cp',cp,'-d',str(classes)]+[str(x) for x in (root/'src').rglob('*.java')],check=True)
jar=build/'armeria-browser-1.2.0.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nImplementation-Version: 1.2.0\n\n')
    for directory,prefix in [(classes,''),(root/'resources',''),(root/'src','META-INF/armeria-sources/')]:
        for f in sorted(directory.rglob('*')):
            if f.is_file():z.write(f,prefix+f.relative_to(directory).as_posix())
if a.test:
    tests=build/'tests';shutil.rmtree(tests,ignore_errors=True);tests.mkdir()
    subprocess.run(compiler+['-cp',str(jar)+os.pathsep+cp,'-d',str(tests)]+[str(x) for x in (root/'tests').rglob('*.java')],check=True)
    for name in ['ShopVerification','BatchVerification','Verification','SupportVerification']:
        result=subprocess.run(['java','-cp',os.pathsep.join([str(tests),str(jar),cp]),'com.armeria.'+name],capture_output=True,text=True)
        (build/(name+'.txt')).write_text(result.stdout+result.stderr)
        print(result.stdout,end='')
        if result.returncode:raise RuntimeError(result.stderr)
print(jar)
