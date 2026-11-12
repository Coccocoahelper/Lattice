import net.hollowcube.polar.*;
import java.nio.file.*;
import java.util.*;
public class Probe2 {
  static boolean isAir(String st){ return st.equals("air")||st.equals("minecraft:air"); }
  public static void main(String[] a) throws Exception {
    for (String f : a) {
      PolarWorld w = PolarReader.read(Files.readAllBytes(Path.of(f)));
      long[] perY = new long[64];
      int mincx=Integer.MAX_VALUE,maxcx=Integer.MIN_VALUE,mincz=Integer.MAX_VALUE,maxcz=Integer.MIN_VALUE;
      long non=0, air=0;
      for (PolarChunk c : w.chunks()) {
        mincx=Math.min(mincx,c.x()); maxcx=Math.max(maxcx,c.x());
        mincz=Math.min(mincz,c.z()); maxcz=Math.max(maxcz,c.z());
        for (int i=0;i<c.sections().length;i++) {
          PolarSection s=c.sections()[i]; if (s==null) continue;
          int y=w.minSection()+i; String[] p=s.blockPalette(); int[] d=s.blockData();
          if (d==null) {
            if (isAir(p[0])) air+=4096; else { non+=4096; if(y>=0&&y<64) perY[y+32]+=4096; }
          } else for (int v : d) {
            String st=p[v];
            if (isAir(st)) air++; else { non++; perY[y+32]++; }
          }
        }
      }
      System.out.println("FILE "+new java.io.File(f).getName()+" chunks="+w.chunks().size()+" cx="+mincx+".."+maxcx+" cz="+mincz+".."+maxcz+" nonAir="+non+" air="+air);
      StringBuilder sb=new StringBuilder("Ycounts:");
      for(int i=0;i<64;i++) if(perY[i]>0) sb.append(" ").append(i-32).append("=").append(perY[i]);
      System.out.println(sb);
    }
  }
}
