use godot::prelude::*;
use godot::classes::{Node, INode};

#[derive(GodotClass)]
#[class(base=Node)]
pub struct GameStateBridge {
    pub position: Vector3,
    pub action: GString,
    base: Base<Node>,
}

#[godot_api]
impl INode for GameStateBridge {
    fn init(base: Base<Node>) -> Self {
        Self {
            position: Vector3::ZERO,
            action: GString::from("idle"),
            base,
        }
    }
}

#[godot_api]
impl GameStateBridge {
    #[func]
    fn update_from_json(&mut self, json: GString) {
        godot_print!("GameStateBridge received: {}", json);
    }

    #[func]
    fn get_position(&self) -> Vector3 {
        self.position
    }

    #[func]
    fn set_player_position(&mut self, pos: Vector3) {
        self.position = pos;
    }

    #[func]
    fn set_action(&mut self, action: GString) {
        self.action = action;
    }
}
