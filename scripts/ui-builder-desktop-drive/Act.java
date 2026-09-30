import java.awt.*; import java.awt.event.*; import java.awt.image.*; import javax.imageio.*; import java.io.*;
public class Act { public static void main(String[] a) throws Exception {
  Robot r = new Robot(); r.setAutoDelay(60);
  int i=0; String out=null;
  while(i<a.length){ switch(a[i]){
    case "click": r.mouseMove(Integer.parseInt(a[i+1]),Integer.parseInt(a[i+2])); r.delay(150); r.mousePress(InputEvent.BUTTON1_DOWN_MASK); r.delay(80); r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); r.delay(700); i+=3; break;
    case "wait": r.delay(Integer.parseInt(a[i+1])); i+=2; break;
    case "key": r.keyPress(Integer.parseInt(a[i+1])); r.keyRelease(Integer.parseInt(a[i+1])); r.delay(300); i+=2; break;
    case "shot": out=a[i+1]; r.delay(500); ImageIO.write(r.createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())),"png",new File(out)); i+=2; break;
    default: throw new RuntimeException(a[i]); } } } }
