// Cross-project import — see federated-remotes-three-wizards.spec.ts for why
// the spec lives in src/ instead of projects/games-vn-maker/. Only the host
// 'hugmesh-games-frontend' project has a karma test target, and its
// require.context root is src/app, so engine specs for the maker remotes
// import the pure engine module via a relative path.
import {
  VnPlayerChoice,
  VnPlayerLine,
  VnPlayerScene,
  advance,
  choose,
  enterScene,
  pickStartScene,
  withChoices,
} from '../../projects/games-vn-maker/src/app/vn-player-engine';

// Pins the VN preview traversal contract: start at the entryPoint scene,
// walk lines by lineIndex, surface a line's choices, choose() jumps to the
// target scene, and a terminal scene ends gracefully. All pure-function —
// no DOM / Angular / HTTP — so this runs without TestBed.
describe('vn-player-engine', () => {
  // A two-scene VN: scene-a is the entry point, scene-b is the branch target.
  const sceneA: VnPlayerScene = { id: 'scene-a', sortOrder: 1, entryPoint: true, backgroundId: 'bg-a' };
  const sceneB: VnPlayerScene = { id: 'scene-b', sortOrder: 0, entryPoint: false };
  const scenes = [sceneB, sceneA]; // deliberately out of order

  // scene-a lines (deliberately out of lineIndex order to prove ordering).
  const a1: VnPlayerLine = { id: 'a1', sceneId: 'scene-a', lineIndex: 0, characterId: 'dani', text: 'Hello.' };
  const a2: VnPlayerLine = { id: 'a2', sceneId: 'scene-a', lineIndex: 1, text: 'A narration line.' };
  const a3: VnPlayerLine = { id: 'a3', sceneId: 'scene-a', lineIndex: 2, characterId: 'dani', text: 'Pick a path.' };
  const aLines = [a3, a1, a2];

  // a3 has two choices; one points at scene-b.
  const choiceLeft: VnPlayerChoice = { id: 'cL', lineId: 'a3', label: 'Go left', targetSceneId: 'scene-b', sortOrder: 1 };
  const choiceRight: VnPlayerChoice = { id: 'cR', lineId: 'a3', label: 'Go right', targetSceneId: 'scene-b', sortOrder: 0 };

  // scene-b lines.
  const b1: VnPlayerLine = { id: 'b1', sceneId: 'scene-b', lineIndex: 0, text: 'You arrived.' };
  const bLines = [b1];

  it('starts at the entryPoint scene (not lowest sortOrder)', () => {
    const start = pickStartScene(scenes);
    expect(start).toBe(sceneA);
  });

  it('falls back to the lowest sortOrder scene when none is the entry point', () => {
    const noEntry = [{ ...sceneA, entryPoint: false }, sceneB];
    const start = pickStartScene(noEntry);
    expect(start?.id).toBe('scene-b'); // sortOrder 0 < 1
  });

  it('enters a scene on its first line by lineIndex order', () => {
    const state = enterScene(sceneA, aLines);
    expect(state.sceneId).toBe('scene-a');
    expect(state.line?.id).toBe('a1'); // lineIndex 0
    expect(state.done).toBeFalse();
    expect(state.choices.length).toBe(0);
  });

  it('advances lines by ascending lineIndex', () => {
    let state = enterScene(sceneA, aLines);
    expect(state.line?.id).toBe('a1');
    state = advance(state, aLines);
    expect(state.line?.id).toBe('a2'); // lineIndex 1
    state = advance(state, aLines);
    expect(state.line?.id).toBe('a3'); // lineIndex 2
    expect(state.done).toBeFalse();
  });

  it('surfaces (sorted) choices on a line that has them', () => {
    let state = enterScene(sceneA, aLines);
    state = advance(state, aLines); // a2
    state = advance(state, aLines); // a3
    state = withChoices(state, [choiceLeft, choiceRight]);
    expect(state.choices.map(c => c.id)).toEqual(['cR', 'cL']); // sorted by sortOrder
    expect(state.choices.length).toBe(2);
  });

  it('choose() jumps to the target scene first line', () => {
    const state = choose(choiceLeft, sceneB, bLines);
    expect(state.sceneId).toBe('scene-b');
    expect(state.line?.id).toBe('b1');
    expect(state.done).toBeFalse();
    expect(state.choices.length).toBe(0);
  });

  it('ends gracefully when advancing off the last line of a scene with no choices', () => {
    const state = enterScene(sceneB, bLines); // single-line terminal scene
    const next = advance(state, bLines);
    expect(next.done).toBeTrue();
    expect(next.line).toBeNull();
  });
});
