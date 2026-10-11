import com.caf.lccustomcity.config.HighwayStyleConfig;
import java.nio.file.*;
import java.util.*;

/** Configuration regression; actual 8.3.9 part overrides run in HighwaySurfaceSmokeTest. */
public final class HighwayStyleSmokeTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);
        List<String> warnings=new ArrayList<>();
        Path missing=root.resolve("missing");Files.createDirectories(missing);
        Files.deleteIfExists(missing.resolve(HighwayStyleConfig.FILE_NAME));
        check(HighwayStyleConfig.load(missing,warnings),"missing config uses enabled default");
        check(Files.isRegularFile(missing.resolve(HighwayStyleConfig.FILE_NAME)),"template written");
        Path configured=root.resolve("configured");Files.createDirectories(configured);
        Path file=configured.resolve(HighwayStyleConfig.FILE_NAME);
        Files.writeString(file,"{\""+HighwayStyleConfig.KEY+"\":false}");
        check(!HighwayStyleConfig.load(configured,warnings)&&!HighwayStyleConfig.enabled(),"false honoured");
        check(warnings.isEmpty(),"valid config warnings");
        Files.writeString(file,"{invalid");
        check(HighwayStyleConfig.load(configured,warnings)&&!warnings.isEmpty(),"broken config falls back");
        HighwayStyleConfig.install(HighwayStyleConfig.DEFAULT);
        System.out.println("Highway config: PASS");
    }
}