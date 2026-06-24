import { Component, ChangeDetectionStrategy } from '@angular/core';

@Component({
  selector: 'app-about',
  standalone: true,
  template: `<div class="about"></div>`,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [`:host { display:block; min-height:100vh; background:#0a0a12; }`],
})
export class AboutComponent {}
