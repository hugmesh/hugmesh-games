use godot::prelude::*;
use godot::classes::{CharacterBody3D, ICharacterBody3D};

#[derive(GodotClass)]
#[class(base=CharacterBody3D)]
pub struct PlayerController {
    #[export]
    speed: f32,
    #[export]
    jump_velocity: f32,
    gravity: f32,
    base: Base<CharacterBody3D>,
}

#[godot_api]
impl ICharacterBody3D for PlayerController {
    fn init(base: Base<CharacterBody3D>) -> Self {
        Self {
            speed: 5.0,
            jump_velocity: 4.5,
            gravity: 9.8,
            base,
        }
    }

    fn physics_process(&mut self, delta: f64) {
        let mut velocity = self.base().get_velocity();

        if !self.base().is_on_floor() {
            velocity.y -= self.gravity * delta as f32;
        }

        self.base_mut().set_velocity(velocity);
        self.base_mut().move_and_slide();
    }
}

#[godot_api]
impl PlayerController {
    #[func]
    fn apply_movement(&mut self, direction: Vector3) {
        let mut velocity = self.base().get_velocity();
        let horizontal = direction * self.speed;
        velocity.x = horizontal.x;
        velocity.z = horizontal.z;
        self.base_mut().set_velocity(velocity);
    }

    #[func]
    fn jump(&mut self) {
        if self.base().is_on_floor() {
            let mut velocity = self.base().get_velocity();
            velocity.y = self.jump_velocity;
            self.base_mut().set_velocity(velocity);
        }
    }
}
