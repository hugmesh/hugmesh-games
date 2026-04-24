use godot::prelude::*;
use godot::classes::{Node3D, INode3D};

#[derive(GodotClass)]
#[class(base=Node3D)]
pub struct CaveScene {
    #[export]
    cave_width: f32,
    #[export]
    cave_height: f32,
    #[export]
    cave_depth: f32,
    element_count: i32,
    base: Base<Node3D>,
}

#[godot_api]
impl INode3D for CaveScene {
    fn init(base: Base<Node3D>) -> Self {
        Self {
            cave_width: 80.0,
            cave_height: 20.0,
            cave_depth: 120.0,
            element_count: 0,
            base,
        }
    }

    fn ready(&mut self) {
        godot_print!("CaveScene ready: {}x{}x{}", self.cave_width, self.cave_height, self.cave_depth);
    }
}

#[godot_api]
impl CaveScene {
    #[signal]
    fn config_applied(element_count: i32);

    /// Called by scene_loader.gd after all API elements are spawned.
    #[func]
    fn on_scene_loaded(&mut self, element_count: i32) {
        self.element_count = element_count;
        godot_print!("CaveScene: {} elements loaded from API", element_count);
        self.base_mut().emit_signal(
            "config_applied",
            &[element_count.to_variant()],
        );
    }

    #[func]
    fn get_cave_bounds(&self) -> Vector3 {
        Vector3::new(self.cave_width, self.cave_height, self.cave_depth)
    }

    #[func]
    fn get_element_count(&self) -> i32 {
        self.element_count
    }
}
