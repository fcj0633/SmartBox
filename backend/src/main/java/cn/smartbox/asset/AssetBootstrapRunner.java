package cn.smartbox.asset;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

@Component
@Order(2)
@ConditionalOnProperty(name="smartbox.assets.bootstrap",havingValue="true")
public class AssetBootstrapRunner implements ApplicationRunner {
    private final AssetBootstrapService service;private final DemoAssetConfig config;
    public AssetBootstrapRunner(AssetBootstrapService service,DemoAssetConfig config){this.service=service;this.config=config;}
    @Override public void run(ApplicationArguments args){service.initialize(config.seed());System.out.println("SmartBox asset initialization completed; existing assignments and device grants preserved.");}
}
