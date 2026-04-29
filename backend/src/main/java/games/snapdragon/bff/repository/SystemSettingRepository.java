package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.SystemSetting;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface SystemSettingRepository extends ReactiveCrudRepository<SystemSetting, String> {
}
