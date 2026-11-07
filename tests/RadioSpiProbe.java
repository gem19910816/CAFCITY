import java.util.ServiceLoader;
import com.november.mcphone.api.client.app.IPhoneApp;

public class RadioSpiProbe {
    public static void main(String[] args) {
        long count=ServiceLoader.load(IPhoneApp.class,IPhoneApp.class.getClassLoader()).stream()
                .filter(provider->provider.type().getName().equals("com.caf.lccustomcity.client.radio.RadioApp"))
                .map(provider->provider.get()).filter(app->app.isPreinstalled()&&app.opensInsidePhone()).count();
        if(count!=1)throw new AssertionError("Expected exactly one preinstalled in-phone radio app, got "+count);
        System.out.println("PASS actual cross-JAR service discovery; exactly one preinstalled radio app");
    }
}
