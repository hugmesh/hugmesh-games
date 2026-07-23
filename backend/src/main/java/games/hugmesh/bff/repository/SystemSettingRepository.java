package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.SystemSetting;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface SystemSettingRepository extends ReactiveCrudRepository<SystemSetting, String> {
}
