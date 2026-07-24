"use strict";

const reader = document.getElementById("reader");
const text = localStorage.getItem("kq.edgeReaderText") || "";
const title = localStorage.getItem("kq.edgeReaderTitle") || "KanjiQuiz";
document.title = title;

for (const block of text.split(/\n\n+/).filter(Boolean)) {
  const section = document.createElement("section");
  for (const line of block.split(/\n+/).filter(Boolean)) {
    const paragraph = document.createElement("p");
    paragraph.textContent = line;
    section.appendChild(paragraph);
  }
  reader.appendChild(section);
}
