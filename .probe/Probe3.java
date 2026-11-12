import net.hollowcube.polar.*; import java.nio.file.*;
public class Probe3 { public static void main(String[] a) throws Exception {
  PolarWorld w=PolarReader.read(Files.readAllBytes(Path.of(a[0])));
  PolarChunk c=w.chunks().iterator().next();
  System.out.println("chunk "+c.x()+","+c.z()+" sections="+c.sections().length+" heightmaps="+c.heightmaps().length);
  for (int i=0;i<c.sections().length;i++){ PolarSection s=c.sections()[i]; if(s==null){System.out.println(i+" null");continue;}
    System.out.println("i="+i+" wy="+(w.minSection()+i)+" empty="+s.isEmpty()+" bpal="+s.blockPalette().length+" bdata="+(s.blockData()==null?"null":s.blockData().length)+" biopal="+(s.biomePalette()==null?"null":s.biomePalette().length)+" biodata="+(s.biomeData()==null?"null":s.biomeData().length)+" sky="+s.skyLightContent()+" sl="+(s.skyLight()==null?"null":s.skyLight().length));
  }
  for(int h=0;h<c.heightmaps().length;h++) System.out.println("hm"+h+" len="+(c.heightmaps()[h]==null?"null":c.heightmaps()[h].length)+" first="+(c.heightmaps()[h]==null?"":c.heightmaps()[h][0]));
}}
