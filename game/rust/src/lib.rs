use godot::prelude::*;

mod cave_scene;
mod player;
mod game_state;

pub use cave_scene::CaveScene;
pub use player::PlayerController;
pub use game_state::GameStateBridge;

struct ThreeWizardsExtension;

#[gdextension]
unsafe impl ExtensionLibrary for ThreeWizardsExtension {}
