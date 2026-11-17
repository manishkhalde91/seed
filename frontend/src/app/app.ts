import { Component, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * Minimal root component: a heading and the router outlet. The application shell, layout and
 * styling arrive in story 10.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly title = signal('Seed');
}
