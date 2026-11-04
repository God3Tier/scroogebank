import "../styles/StaffAccessTicker.css";

const notices = [
  {
    title: "How access works",
    message:
      "Go to Login and sign in with your staff account. The portal opens your assigned Admin or Agent workspace.",
  },
  {
    title: "Trouble signing in?",
    message: "Contact your IT help desk for help accessing your staff account.",
  },
];

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