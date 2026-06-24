import { Component, OnInit, inject, signal, ChangeDetectionStrategy } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { FriendsService, Relationship } from '../services/friends.service';
import { NotificationService } from '../services/notification.service';

/**
 * Self-service friend graph + block management.
 *
 * <p>Routed at {@code /friends} from the host shell; the side nav links
 * here from the user dropdown. Logically a federated remote candidate
 * but lives in-host for now to keep the data plumbing landing in one
 * PR. Promoting to a federated bundle is mechanical when we want it.
 */
@Component({
  selector: 'app-friends',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './friends.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './friends.component.scss',
})
export class FriendsComponent implements OnInit {
  private readonly svc    = inject(FriendsService);
  private readonly notify = inject(NotificationService);

  readonly friends  = signal<Relationship[]>([]);
  readonly incoming = signal<Relationship[]>([]);
  readonly outgoing = signal<Relationship[]>([]);
  readonly blocked  = signal<Relationship[]>([]);
  readonly loading  = signal(false);

  newFriendId = '';

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.loading.set(true);
    let pending = 4;
    const done = () => { if (--pending === 0) this.loading.set(false); };
    this.svc.friends().subscribe({  next: l => { this.friends.set(l);  done(); }, error: done });
    this.svc.incoming().subscribe({ next: l => { this.incoming.set(l); done(); }, error: done });
    this.svc.outgoing().subscribe({ next: l => { this.outgoing.set(l); done(); }, error: done });
    this.svc.blocks().subscribe({   next: l => { this.blocked.set(l);  done(); }, error: done });
  }

  request(): void {
    const target = this.newFriendId.trim();
    if (!target) {
      this.notify.warn('Enter a casdoor id first.');
      return;
    }
    this.svc.request(target).subscribe({
      next: () => { this.newFriendId = ''; this.notify.success(`Friend request sent to ${target}.`); this.refresh(); },
      error: () => { /* interceptor surfaced the toast */ },
    });
  }

  accept(other: string): void {
    this.svc.accept(other).subscribe({
      next: () => { this.notify.success(`You and ${other} are now friends.`); this.refresh(); },
      error: () => { /* toast already shown */ },
    });
  }

  remove(other: string): void {
    this.svc.remove(other).subscribe({
      next: () => { this.notify.info(`${other} removed.`); this.refresh(); },
      error: () => {},
    });
  }

  block(other: string): void {
    this.svc.block(other).subscribe({
      next: () => { this.notify.success(`${other} blocked. Their sessions are hidden from your lobbies.`); this.refresh(); },
      error: () => {},
    });
  }

  unblock(other: string): void {
    this.svc.unblock(other).subscribe({
      next: () => { this.notify.info(`${other} unblocked.`); this.refresh(); },
      error: () => {},
    });
  }
}
