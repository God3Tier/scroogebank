import "../styles/StaffAccessTicker.css";
import { staffAccessNotices as notices } from "../data/staffAccessNotices";

export default function StaffAccessTicker() {
  const renderNotices = () =>
    notices.map(({ title, message }) => (
      <li key={title}>
        <strong>{title}</strong>
        <span aria-hidden="true"> · </span>
        {message}
      </li>
    ));

  return (
    <section className="staff-ticker" aria-label="Staff access notices">
      <span className="staff-ticker__label" aria-hidden="true">
        Staff access
      </span>

      <div className="staff-ticker__viewport">
        <div className="staff-ticker__track">
          <ul className="staff-ticker__notices">{renderNotices()}</ul>
          <ul className="staff-ticker__notices" aria-hidden="true">
            {renderNotices()}
          </ul>
        </div>
      </div>
    </section>
  );
}