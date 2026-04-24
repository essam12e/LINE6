const revealItems = document.querySelectorAll(".reveal");
const siteHeader = document.querySelector(".site-header");
const quoteForm = document.getElementById("quoteForm");
const filterButtons = [...document.querySelectorAll(".filter-btn")];
const productCards = [...document.querySelectorAll(".product-card")];
const productOrderButtons = [...document.querySelectorAll(".product-order-btn")];
const productNameInput = document.querySelector('[name="productName"]');
const productCategoryInput = document.querySelector('[name="productCategory"]');

const revealObserver = new IntersectionObserver((entries) => {
  entries.forEach((entry) => {
    if (!entry.isIntersecting) return;
    entry.target.classList.add("visible");
    revealObserver.unobserve(entry.target);
  });
}, { threshold: 0.14 });

revealItems.forEach((item) => revealObserver.observe(item));

function syncHeaderState() {
  if (!siteHeader) return;
  siteHeader.classList.toggle("scrolled", window.scrollY > 24);
}

function applyProductFilter(category) {
  if (!productCards.length) return;

  productCards.forEach((card) => {
    const matches = category === "all" || card.dataset.category === category;
    card.style.display = matches ? "" : "none";
  });

  filterButtons.forEach((button) => {
    button.classList.toggle("active", button.dataset.filter === category);
  });
}

function fillProductForm({ name = "", category = "" }) {
  if (productNameInput) productNameInput.value = name;
  if (productCategoryInput) productCategoryInput.value = category;
}

filterButtons.forEach((button) => {
  button.addEventListener("click", () => applyProductFilter(button.dataset.filter || "all"));
});

productOrderButtons.forEach((button) => {
  button.addEventListener("click", () => {
    fillProductForm({
      name: button.dataset.product || "",
      category: button.dataset.categoryName || "",
    });
    quoteForm?.scrollIntoView({ behavior: "smooth", block: "start" });
  });
});

if (quoteForm) {
  quoteForm.addEventListener("submit", (event) => {
    event.preventDefault();

    const formData = new FormData(quoteForm);
    const name = (formData.get("name") || "").toString().trim();
    const phone = (formData.get("phone") || "").toString().trim();
    const service = (formData.get("service") || "").toString().trim();
    const details = (formData.get("details") || "").toString().trim();
    const productName = (formData.get("productName") || "").toString().trim();
    const productCategory = (formData.get("productCategory") || "").toString().trim();

    const messageLines = [
      "السلام عليكم",
      `الاسم: ${name}`,
      `رقم التواصل: ${phone}`,
      `الخدمة المطلوبة: ${service || "منتجات خط ستة"}`,
    ];

    if (productCategory) messageLines.push(`التصنيف: ${productCategory}`);
    if (productName) messageLines.push(`المنتج: ${productName}`);

    messageLines.push(`تفاصيل الطلب: ${details || "أرغب بمعرفة السعر والتفاصيل المناسبة"}`);
    messageLines.push("", "وصلتني من موقع خط ستة وأرغب بطلب أو عرض سعر.");

    const url = `https://wa.me/966569410657?text=${encodeURIComponent(messageLines.join("\n"))}`;
    window.open(url, "_blank", "noopener,noreferrer");
  });
}

if (productCards.length) {
  const urlParams = new URLSearchParams(window.location.search);
  const requestedCategory = urlParams.get("category");
  const requestedProduct = urlParams.get("product");
  const initialCategory = filterButtons.some((btn) => btn.dataset.filter === requestedCategory) ? requestedCategory : "all";

  applyProductFilter(initialCategory);

  if (requestedProduct) {
    const matchingButton = productOrderButtons.find((button) => button.dataset.product === requestedProduct);
    if (matchingButton) {
      fillProductForm({
        name: matchingButton.dataset.product || "",
        category: matchingButton.dataset.categoryName || "",
      });
    }
  }
} else if (filterButtons.length) {
  applyProductFilter("all");
}

syncHeaderState();
window.addEventListener("scroll", syncHeaderState, { passive: true });
