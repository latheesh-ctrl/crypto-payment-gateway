package com.cpg.gateway.asset;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/assets")
public class AssetController {

    private final AssetRepository assets;

    public AssetController(AssetRepository assets) {
        this.assets = assets;
    }

    @GetMapping
    public ListResponse<Asset> list() {
        return new ListResponse<>(assets.findEnabled());
    }

    public record ListResponse<T>(List<T> data) {
    }
}
